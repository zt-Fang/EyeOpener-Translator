package io.github.ztfang.eye.engine.network

import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GoogleReachabilityChecker.describe] 的文案契约测试。
 *
 * 存在理由：真机上踩过一次坑 —— `dl.google.com` 被解析到国内 CDN 节点
 * （`211.95.34.129`，出示合法 Google 证书，TLS 校验通过），于是可达性探测判定
 * `Reachable`，诊断文案只说"网络可正常访问 Google 服务器"，**完全掩盖了真正原因**
 * （代理分流把模型下载域名放走直连了），用户看到的只是"下载未完成，可能是限流"，
 * 于是反复重试、反复失败。
 *
 * 因此这里把"解析 IP 必须出现在文案里"钉成不变量：任何人日后为了"简洁"把 IP 从
 * 文案中删掉，都会在这里被拦下。
 */
class GoogleReachabilityDescribeTest {
    private val checker = GoogleReachabilityChecker(OkHttpClient())

    private val host = GoogleReachabilityChecker.DEFAULT_PROBE_HOST

    @Test
    fun `Reachable 必须写出解析 IP`() {
        val text =
            checker.describe(
                GoogleReachabilityChecker.Reachability.Reachable(host, "211.95.34.129"),
            )

        assertTrue("文案应包含域名：$text", text.contains(host))
        assertTrue("文案应包含解析 IP，否则用户无法判断是否走错节点：$text", text.contains("211.95.34.129"))
    }

    @Test
    fun `Reachable 的 IP 必须标明来自本地 DNS`() {
        val text =
            checker.describe(
                GoogleReachabilityChecker.Reachability.Reachable(host, "211.95.34.129"),
            )

        // 实测教训：TUN/VPN 代理下本地解析到的 IP 与实际出口可以完全不同
        // （dl.google.com 本地解析 211.95.34.129，实际被 Clash 路由到日本节点）。
        // 文案若写成"解析为"，用户会误读成"分流没生效"而去乱改配置。
        assertTrue(
            "必须标明该 IP 来自本地 DNS，否则会被误读为实际出口：$text",
            text.contains("本地解析为"),
        )
    }

    @Test
    fun `Reachable 有耗时时必须写出来`() {
        val text =
            checker.describe(
                GoogleReachabilityChecker.Reachability
                    .Reachable(host, "211.95.34.129", elapsedMs = 2045),
            )

        // 耗时是唯一能区分"哪个域名是瓶颈"的指标：实测两个域名都 TLS 通过、都判定
        // Reachable，但 dl.google.com 走代理用 2045ms、redirector.gvt1.com 直连只用 179ms。
        // 不写出这个数字，用户就看不出"代理节点比直连还慢"。
        assertTrue("必须写出探测耗时：$text", text.contains("2045ms"))
    }

    @Test
    fun `Reachable 未测量耗时时不应出现 0ms 噪音`() {
        val text =
            checker.describe(
                GoogleReachabilityChecker.Reachability
                    .Reachable(host, "1.2.3.4", elapsedMs = 0),
            )

        assertFalse("未测量时不应显示耗时：$text", text.contains("耗时"))
    }

    @Test
    fun `探测列表必须同时覆盖入口与真正的数据源`() {
        val hosts = GoogleReachabilityChecker.DEFAULT_PROBE_HOSTS

        // ML Kit 的模型数据不是从入口域名传的，实测链路是
        // dl.google.com → redirector.gvt1.com → *.gvt1-cn.com。
        // 两个域名的分流结果可以完全相反，漏探数据源就会得出"网络没问题"的错误结论。
        assertTrue(
            "必须包含入口域名：$hosts",
            hosts.contains(GoogleReachabilityChecker.PROBE_HOST_ENTRY),
        )
        assertTrue(
            "必须包含真正的数据源域名：$hosts",
            hosts.contains(GoogleReachabilityChecker.PROBE_HOST_CDN),
        )
    }

    @Test
    fun `Reachable 解析失败时要明说解析失败而非静默省略`() {
        val text =
            checker.describe(
                GoogleReachabilityChecker.Reachability.Reachable(host, null),
            )

        assertTrue("应显式提示域名解析失败：$text", text.contains("解析失败"))
    }

    @Test
    fun `Intercepted 必须同时写出域名与解析 IP`() {
        val text =
            checker.describe(
                GoogleReachabilityChecker.Reachability.Intercepted(
                    host = host,
                    resolvedIp = "127.0.0.1",
                    detail = "TLS 证书校验失败",
                ),
            )

        assertTrue(text.contains(host))
        assertTrue("被污染时必须给出指向的 IP：$text", text.contains("127.0.0.1"))
        assertTrue("应保留底层细节：$text", text.contains("TLS 证书校验失败"))
    }

    @Test
    fun `Unreachable 必须同时写出域名与解析 IP`() {
        val text =
            checker.describe(
                GoogleReachabilityChecker.Reachability.Unreachable(
                    host = host,
                    resolvedIp = "10.0.0.1",
                    detail = "连接被拒绝或重置",
                ),
            )

        assertTrue(text.contains(host))
        assertTrue("应给出解析 IP 辅助排障：$text", text.contains("10.0.0.1"))
    }

    @Test
    fun `Intercepted 无 IP 时域名与后续文字之间不能黏连`() {
        val text =
            checker.describe(
                GoogleReachabilityChecker.Reachability.Intercepted(host, null, "TLS 证书校验失败"),
            )

        assertFalse("域名后缺分隔符会导致黏连文案：$text", text.contains("${host}但"))
        assertTrue("应保留逗号分隔：$text", text.contains("，但对方不是 Google"))
    }

    @Test
    fun `文案不应包含 Markdown 语法`() {
        val samples =
            listOf(
                GoogleReachabilityChecker.Reachability.Reachable(host, "1.2.3.4", 100),
                GoogleReachabilityChecker.Reachability.Reachable(host, null),
                GoogleReachabilityChecker.Reachability.Intercepted(host, "127.0.0.1", "x", 200),
                GoogleReachabilityChecker.Reachability.Unreachable(host, null, "y", 300),
            )

        // 诊断弹窗与日志都是纯文本渲染，写成 **加粗** 会把星号原样显示给用户
        // （真机上踩过：`**本地 DNS**` 直接显示成了星号）。
        samples.forEach { sample ->
            val text = checker.describe(sample)
            assertFalse("纯文本渲染，不得包含 Markdown 标记：$text", text.contains("**"))
        }
    }

    @Test
    fun `文案不应出现空占位`() {
        val samples =
            listOf(
                GoogleReachabilityChecker.Reachability.Reachable(host, "1.2.3.4"),
                GoogleReachabilityChecker.Reachability.Reachable(host, null),
                GoogleReachabilityChecker.Reachability.Intercepted(host, null, "x"),
                GoogleReachabilityChecker.Reachability.Unreachable(host, null, "y"),
            )

        samples.forEach { sample ->
            val text = checker.describe(sample)
            assertTrue("文案不应为空：$sample", text.isNotBlank())
            assertFalse("文案不应出现 null 字样：$text", text.contains("null"))
            assertFalse("文案不应出现双句号：$text", text.contains("。。"))
        }
    }
}
