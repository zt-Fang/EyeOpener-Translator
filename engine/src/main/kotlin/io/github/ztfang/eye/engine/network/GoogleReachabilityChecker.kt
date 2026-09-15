package io.github.ztfang.eye.engine.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InterruptedIOException
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google 服务可达性探测 —— **通用实现，不依赖任何特定代理/VPN 工具**。
 *
 * 存在的理由：ML Kit 的离线翻译模型必须从 Google 服务器下载，国内环境下这一步依赖用户
 * 自己的代理。失败原因五花八门（未开代理 / 代理只覆盖部分域名 / DNS 被污染 / ROM 无 GMS），
 * 若只报"下载超时"，用户和开发者都无从下手。
 *
 * 判据设计：**只看 TLS 握手能否通过 OkHttp 的证书校验**。
 * 真 Google 的证书由 Google Trust Services 签发且 SAN 覆盖目标域名，被污染或被劫持的
 * 地址无法伪造出有效证书。因此无需维护 Google IP 段列表，也能准确区分
 * 「连到了真 Google」/「连上了但不是 Google」/「根本连不上」三种情况。
 *
 * 注意：本探测使用 App 自身的网络栈，而 ML Kit 的下载由 GMS 进程执行，两者受代理
 * 分流规则影响可能不同。因此探测结果**只用于生成提示文案**，不作为阻断下载的依据。
 */
@Singleton
class GoogleReachabilityChecker
    @Inject
    constructor(
        private val okHttpClient: OkHttpClient,
    ) {
        /** 探测结果 */
        sealed interface Reachability {
            /**
             * TLS 证书校验通过，连到的是 Google 运营的服务器。
             *
             * ⚠️ **[resolvedIp] 是本地 DNS 的解析结果，不是实际出口地址。**
             * 实测（Clash Meta TUN 模式）：`dl.google.com` 本地解析为 `211.95.34.129`
             * （国内 CDN 节点），但 Clash 按域名规则把它路由到了日本节点——
             * 解析 IP 与实际出口完全不同。因此这个字段只能作为**线索**，
             * 不能当作"分流是否走错"的判据；文案里必须说明它是本地解析结果。
             */
            data class Reachable(
                val host: String,
                val resolvedIp: String?,
                /**
                 * 探测耗时（毫秒），0 表示未测量。
                 *
                 * 这是**唯一能区分"哪个域名是瓶颈"的指标**：实测两个域名都 TLS 通过、
                 * 都判定 `Reachable`，但耗时相差一个数量级——`dl.google.com` 走代理用了
                 * 2045ms，而 `redirector.gvt1.com` 直连只用 179ms。
                 * 不报耗时，用户根本看不出"代理节点比直连还慢"这种反直觉的情况。
                 */
                val elapsedMs: Long = 0,
            ) : Reachability

            /**
             * 请求被拦截：对方有响应，但**不是** Google。
             * 典型场景：DNS 被污染指向国内 IP；代理分流规则未覆盖该域名，请求走了直连。
             */
            data class Intercepted(
                val host: String,
                val resolvedIp: String?,
                val detail: String,
                /** 探测耗时（毫秒），0 表示未测量 */
                val elapsedMs: Long = 0,
            ) : Reachability

            /**
             * 完全连不上：超时、连接被拒绝/重置、域名解析失败等。
             * 典型场景：未开启代理；代理只覆盖部分应用或域名。
             */
            data class Unreachable(
                val host: String,
                val resolvedIp: String?,
                val detail: String,
                /** 探测耗时（毫秒），0 表示未测量 */
                val elapsedMs: Long = 0,
            ) : Reachability
        }

        /**
         * 探测单个域名是否真的可访问。
         *
         * @param host 默认 [DEFAULT_PROBE_HOST]。但若目的是判断"模型能不能下下来"，
         *   应优先探 [PROBE_HOST_CDN]（真正传数据的域名），或直接用 [checkAll] 把两个都探了。
         */
        suspend fun check(host: String = DEFAULT_PROBE_HOST): Reachability =
            withContext(Dispatchers.IO) {
                val resolvedIp = resolveHost(host)
                // 只计 probe 的耗时（含 OkHttp 自己的 DNS + TCP + TLS），不含上面那次
                // 仅用于文案的 resolveHost——后者有 5s 超时兜底，混进来会污染指标。
                val probeStart = System.currentTimeMillis()
                val outcome = probe(host)
                val elapsedMs = System.currentTimeMillis() - probeStart
                val result =
                    when (outcome) {
                        is ProbeOutcome.Ok ->
                            Reachability.Reachable(host, resolvedIp, elapsedMs)

                        is ProbeOutcome.Intercepted ->
                            Reachability.Intercepted(host, resolvedIp, outcome.detail, elapsedMs)

                        is ProbeOutcome.Unreachable ->
                            Reachability.Unreachable(host, resolvedIp, outcome.detail, elapsedMs)
                    }
                Log.i(
                    TAG,
                    "可达性探测 $host: resolved=$resolvedIp, ${elapsedMs}ms, " +
                        "result=${result.javaClass.simpleName}",
                )
                result
            }

        /**
         * 并行探测多个域名，按入参顺序返回。
         *
         * 为什么必须探**多个**：ML Kit 的模型并不直接从 [PROBE_HOST_ENTRY] 传数据。
         * 实测（Clash Meta TUN）完整链路是：
         * `dl.google.com`（重定向器，探测返回 404）→ `redirector.gvt1.com`
         * → `rN---sn-*.gvt1-cn.com`（真正的数据源）。
         *
         * 只探入口域名会出现"探测说通、下载却卡死"的错位——实测入口命中
         * `DomainKeyword(google) → 日本节点`，而 CDN 域名命中的是 `GeoIP(cn) → DIRECT`，
         * 两者的分流结果可以完全相反。所以探测列表要覆盖到真正的数据源。
         */
        suspend fun checkAll(hosts: List<String> = DEFAULT_PROBE_HOSTS): List<Pair<String, Reachability>> =
            coroutineScope {
                hosts.map { host -> async { host to check(host) } }.awaitAll()
            }

        /**
         * 把探测结果转成给用户看的一句话。
         * 措辞保持通用——不假设用户用的是哪一种代理工具。
         */
        fun describe(result: Reachability): String =
            when (result) {
                is Reachability.Reachable -> {
                    buildString {
                        append("网络可访问 Google 服务器（")
                        append(result.host)
                        if (result.resolvedIp != null) {
                            // 措辞刻意用"本地解析为"而不是"解析为"：TUN/VPN 代理下这个 IP
                            // 只是本地 DNS 的结果，实际出口由代理规则决定，两者可以完全不同
                            // （实测本地解析 211.95.34.129，实际被路由到日本节点）。
                            // 写成"解析为"会让用户误判成"分流没生效"。
                            append(" 本地解析为 ${result.resolvedIp}")
                        } else {
                            append(" 域名解析失败")
                        }
                        // 耗时是**唯一**能区分"哪个域名是瓶颈"的指标：实测两个域名都 TLS 通过、
                        // 都判定 Reachable，但一个走代理用 2045ms、另一个直连只用 179ms。
                        // 不报耗时，用户就看不出"代理节点比直连还慢"这种反直觉情况。
                        if (result.elapsedMs > 0) {
                            append("，耗时 ${result.elapsedMs}ms")
                        }
                        append("）。")
                    }
                }

                is Reachability.Intercepted -> {
                    buildString {
                        append("无法访问 Google 下载服务器：")
                        append(result.host)
                        // 解析不到 IP 时也必须留一个分隔符，否则会输出
                        // "dl.google.com但对方不是 Google" 这种黏连文案。
                        if (result.resolvedIp != null) {
                            append(" 本地解析为 ${result.resolvedIp}，")
                        } else {
                            append("，")
                        }
                        append("但对方不是 Google（${result.detail}）。")
                        append("通常是 DNS 被污染，或代理/VPN 的分流规则没有覆盖该域名。")
                    }
                }

                is Reachability.Unreachable -> {
                    buildString {
                        append("无法连接 Google 下载服务器：")
                        append(result.host)
                        if (result.resolvedIp != null) append("（本地解析为 ${result.resolvedIp}）")
                        append("。${result.detail}。")
                        append("请确认代理/VPN 已开启，且其规则覆盖 Google 服务域名。")
                    }
                }
            }

        private sealed interface ProbeOutcome {
            data object Ok : ProbeOutcome

            data class Intercepted(
                val detail: String,
            ) : ProbeOutcome

            data class Unreachable(
                val detail: String,
            ) : ProbeOutcome
        }

        private suspend fun probe(host: String): ProbeOutcome =
            try {
                withTimeout(PROBE_TIMEOUT_MS) {
                    val request =
                        Request
                            .Builder()
                            .url("https://$host/")
                            .head()
                            .build()
                    probeClient.newCall(request).execute().use {
                        // 只要 TLS 握手通过（OkHttp 已校验证书链与域名），就说明连到的是真 Google。
                        // 状态码无关紧要：根路径可能返回 200 / 301 / 404。
                        ProbeOutcome.Ok
                    }
                }
            } catch (e: TimeoutCancellationException) {
                ProbeOutcome.Unreachable("请求超时（${PROBE_TIMEOUT_MS / 1000}s）")
            } catch (e: javax.net.ssl.SSLException) {
                ProbeOutcome.Intercepted("TLS 证书校验失败，疑似被劫持或指向了假服务器")
            } catch (e: java.net.UnknownHostException) {
                ProbeOutcome.Unreachable("域名解析失败")
            } catch (e: java.net.ConnectException) {
                ProbeOutcome.Unreachable("连接被拒绝或重置")
            } catch (e: InterruptedIOException) {
                ProbeOutcome.Unreachable("请求被中断或超时")
            } catch (e: Exception) {
                ProbeOutcome.Unreachable("${e.javaClass.simpleName}: ${e.message ?: "未知错误"}")
            }

        /**
         * 解析域名，仅用于诊断文案（告诉用户"被解析到了哪个 IP"）。
         *
         * 必须单独加超时：[InetAddress.getByName] 是**阻塞**调用，且不受 [PROBE_TIMEOUT_MS]
         * 约束。DNS 被污染或代理的 DNS 不响应时，这一步可能卡好几秒，而它在
         * `downloadAndWait` 里是**下载前**同步执行的——不加超时就会白等，把下载往后推。
         * 解析结果只影响提示文案，拿不到就返回 null，绝不阻断主流程。
         */
        private suspend fun resolveHost(host: String): String? =
            withTimeoutOrNull(DNS_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    runCatching { InetAddress.getByName(host).hostAddress }.getOrNull()
                }
            }

        /**
         * 探测专用客户端：复用共享连接池，但把超时压到 [PROBE_TIMEOUT_MS]，
         * 避免沿用云端翻译的 30s 连接 / 60s 读取超时让用户干等。
         */
        private val probeClient: OkHttpClient by lazy {
            okHttpClient
                .newBuilder()
                .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
        }

        companion object {
            private const val TAG = "GoogleReachability"

            /**
             * 探测目标之一：GMS 下载 ML Kit 模型时的**入口域名**。
             * 它本身是个重定向器（根路径返回 404），并不直接传输模型数据。
             */
            const val PROBE_HOST_ENTRY = "dl.google.com"

            /**
             * 探测目标之二：**真正的模型数据源**。
             *
             * 实测（Clash Meta TUN）完整链路为 `dl.google.com` → `redirector.gvt1.com`
             * → `rN---sn-*.gvt1-cn.com`。注意 `gvt1-cn.com` 的解析结果落在国内网段，
             * 分流规则若按 `GeoIP(cn)` 直连，就会把模型下载整个放走直连；
             * 而入口域名 `dl.google.com` 可能命中 `DomainKeyword(google)` 正常走代理。
             * 这正是"探测说通、下载却卡死"的成因——所以两个域名都要探。
             */
            const val PROBE_HOST_CDN = "redirector.gvt1.com"

            /** 默认探测列表：入口 + 真正的数据源 */
            val DEFAULT_PROBE_HOSTS = listOf(PROBE_HOST_ENTRY, PROBE_HOST_CDN)

            /** 单域名探测的默认目标（[check] 的默认参数） */
            const val DEFAULT_PROBE_HOST = PROBE_HOST_ENTRY

            /** 单次探测超时：8s（用户等不起更久） */
            private const val PROBE_TIMEOUT_MS = 8_000L

            /**
             * DNS 解析超时：5s。
             * 解析只是为了让诊断文案能写出"被解析到哪个 IP"，属于锦上添花；
             * DNS 不响应时宁可没有这个信息，也不能拖慢后面的模型下载。
             */
            private const val DNS_TIMEOUT_MS = 5_000L
        }
    }
