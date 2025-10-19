package com.example

import Global.env
import Global.serverEnv
import com.example.plugins.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main(args: Array<String>) {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)

    /**
     * Load the main config file. This is also shared with client versions of Ark as well and contains
     * general settings like program arguments, and config folder settings.
     */
    env.load()
    env.get().setArgs(args.toList())

    /**
     * Load the server's config settings next. This is where the Ark server holds all the critical config data
     * that informs it of where it's database is, user and security information, connection rules,
     * and various hosting and server specific settings.
     */
    serverEnv.load()

}

fun Application.module() {
    configureSockets()
    configureSerialization()
    configureMonitoring()
    configureHTTP()
    configureSecurity()
    configureRouting()
    configureApiRoutes()
}
