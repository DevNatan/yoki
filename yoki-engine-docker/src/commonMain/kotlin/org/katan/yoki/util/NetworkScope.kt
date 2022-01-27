package org.katan.yoki.util

import org.katan.yoki.model.network.*

public fun checkScope(scope: String) {
    return require(scope == NetworkLocalScope || scope == NetworkGlobalScope || scope == NetworkSwarmScope) {
        "Network scope must be $NetworkLocalScope, $NetworkGlobalScope or $NetworkSwarmScope"
    }
}