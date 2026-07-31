package com.example.alarmtracker.connector

import android.content.Context

/** The set of available connectors. Add a new integration by appending it here. */
object ConnectorRegistry {

    val all: List<Connector> = listOf(JiraConnector)

    fun byId(id: String): Connector? = all.firstOrNull { it.id == id }

    /** True if any connector currently has stored credentials. */
    fun anyConnected(context: Context): Boolean = all.any { it.isConnected(context) }
}
