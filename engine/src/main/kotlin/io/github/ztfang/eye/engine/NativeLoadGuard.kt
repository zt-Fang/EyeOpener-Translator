package io.github.ztfang.eye.engine

import kotlinx.coroutines.CancellationException

/**
 * 判断某个 [Throwable] 是否为协程取消信号。
 *
 * ### 为什么需要这个判断
 *
 * native 库加载失败（`System.loadLibrary("sherpa-onnx-jni")` 等）抛出的是
 * [UnsatisfiedLinkError] —— 它继承 `LinkageError` → `Error`，**不是 `Exception`**。
 *
 * 早先各引擎（`SherpaOnnxAsrEngine` / `SileroVadEngine` / `VoskAsrEngine`）都用
 * `catch (e: Exception)` 包裹模型初始化，因此**捕不到**这类 Error：它会逃出
 * `withContext`、逃出 `scope.launch`，而项目里没有 `UncaughtExceptionHandler` 兜底，
 * 最终导致**进程崩溃**（32 位设备缺 `libsherpa-onnx-jni.so` 时就会命中，
 * 且 `SileroVadEngine.init()` 在 App 启动阶段就会被调用 → 一开就崩）。
 *
 * 所以这些位置统一改成 `catch (e: Throwable)`。但 `Throwable` 会把
 * [CancellationException] 也一并捕获 —— 而协程取消必须原样抛出，否则会被误当成
 * "模型加载失败"吞掉，破坏结构化并发（上层 `withTimeout` / `cancel()` 失效）。
 * 这就是本函数存在的意义：凡是 `catch (Throwable)` 的地方，先调用它放行取消。
 *
 * 用法：
 * ```
 * } catch (e: Throwable) {
 *     if (e.isCoroutineCancellation()) throw e
 *     ... // 其余一律降级为 Result.failure，不再让 Error 冒泡崩溃
 * }
 * ```
 */
internal fun Throwable.isCoroutineCancellation(): Boolean = this is CancellationException
