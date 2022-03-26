package org.katan.yoki

import org.katan.yoki.engine.YokiEngine
import org.katan.yoki.engine.YokiEngineConfig

public class YokiConfig(
    public val engine: YokiEngine,
    public val logger: YokiLogger
)

public class YokiConfigFactory<T : YokiEngineConfig> {

    internal var engineConfig: T.() -> Unit = {}
    internal var logger: YokiLogger = NoopYokiLogger
}

public fun <T : YokiEngineConfig> YokiConfigFactory<T>.engine(block: T.() -> Unit) {
    engineConfig = block
}

public fun YokiConfigFactory<*>.logger(logger: YokiLogger) {
    this.logger = logger
}

private object NoopYokiLogger : YokiLogger {

    override val defaultLevel: LogLevel
        get() = error("Noop logger do not have a default log level")

    override fun log(level: LogLevel, parameters: Array<Any>) {
    }
}
