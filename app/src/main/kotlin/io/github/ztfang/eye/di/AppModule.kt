package io.github.ztfang.eye.di

import android.content.Context
import androidx.room.Room
import io.github.ztfang.eye.data.local.datastore.SettingsDataStore
import io.github.ztfang.eye.data.local.database.AppDatabase
import io.github.ztfang.eye.data.repository.HistoryRepositoryImpl
import io.github.ztfang.eye.data.repository.ModelRepositoryImpl
import io.github.ztfang.eye.data.repository.SettingsRepositoryImpl
import io.github.ztfang.eye.domain.engine.asr.AsrEngine
import io.github.ztfang.eye.domain.engine.vad.VADEngine
import io.github.ztfang.eye.domain.model.TranslationEngine
import io.github.ztfang.eye.domain.repository.HistoryRepository
import io.github.ztfang.eye.domain.repository.ModelRepository
import io.github.ztfang.eye.domain.repository.SettingsRepository
import io.github.ztfang.eye.domain.usecase.translation.TranslateUseCase
import io.github.ztfang.eye.engine.asr.SherpaOnnxAsrEngine
import io.github.ztfang.eye.engine.asr.VoskAsrEngine
import io.github.ztfang.eye.engine.translation.cloud.CloudTranslationEngine
import io.github.ztfang.eye.engine.translation.llm.LLMTranslationEngine
import io.github.ztfang.eye.engine.translation.mlkit.MlKitTranslationEngine
import io.github.ztfang.eye.engine.vad.SileroVadEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt 依赖注入模块。
 * 定义应用全局单例依赖：DataStore、Repository、引擎实现、UseCase。
 * 所有依赖通过 @Provides 暴露接口类型，实现类通过 @Inject 构造器自动注入。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /** 提供 SettingsDataStore 单例，用于偏好设置存储 */
    @Provides @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): SettingsDataStore
        = SettingsDataStore(context)

    /** 提供 ModelRepository 单例，管理模型文件下载与路径查询 */
    @Provides @Singleton
    fun provideModelRepository(@ApplicationContext context: Context): ModelRepository
        = ModelRepositoryImpl(context)

    /** 提供 SettingsRepository 单例，封装 DataStore 读写接口 */
    @Provides @Singleton
    fun provideSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository = impl

    /** 提供 Room 数据库单例 */
    @Provides @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "eye.db")
            .fallbackToDestructiveMigration()
            .build()

    /** 提供 HistoryDao */
    @Provides @Singleton
    fun provideHistoryDao(db: AppDatabase): io.github.ztfang.eye.data.local.dao.HistoryDao =
        db.historyDao()

    /** 提供 HistoryRepository 单例 */
    @Provides @Singleton
    fun provideHistoryRepository(impl: HistoryRepositoryImpl): HistoryRepository = impl

    /** 提供 VAD 引擎单例（Silero VAD 实现，基于 sherpa-onnx） */
    @Provides @Singleton
    fun provideVadEngine(impl: SileroVadEngine): VADEngine = impl

    /** 提供 ASR 引擎单例（Vosk 多语种流式实现） */
    @Provides @Singleton
    fun provideAsrEngine(impl: VoskAsrEngine): AsrEngine = impl

    /**
     * 提供共享 OkHttpClient 单例。
     * - 30s 连接 / 60s 读取超时，适配云端翻译 API
     * - DEBUG 构建启用 BODY 级日志，方便排查请求/响应
     */
    @Provides @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        // 仅 DEBUG 构建开启详细日志（避免 release 泄露 API Key）
        if (io.github.ztfang.eye.BuildConfig.DEBUG) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.HEADERS
                }
            )
        }
        return builder.build()
    }

    /**
     * 提供翻译用例单例，是 SubtitleViewModel 的唯一翻译入口。
     * - LOCAL  → ML Kit 翻译
     * - CLOUD  → CloudTranslationEngine（内部按 provider 路由到 Papago/百度/DeepL/Azure）
     * - AI     → LLM API 翻译
     */
    @Provides @Singleton
    fun provideTranslateUseCase(
        mlkit: MlKitTranslationEngine,
        cloud: CloudTranslationEngine,
        ai: LLMTranslationEngine
    ): TranslateUseCase = TranslateUseCase(mapOf(
        TranslationEngine.LOCAL to mlkit,
        TranslationEngine.CLOUD to cloud,
        TranslationEngine.AI to ai
    ))
}

