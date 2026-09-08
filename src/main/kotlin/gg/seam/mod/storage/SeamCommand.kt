package gg.seam.mod.storage

import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import org.slf4j.Logger

/**
 * `/seam` — turning the reporter on without hand-editing JSON (MCO-534).
 *
 * A server operator should not have to stop the server, write a config file by hand and start it
 * again just to connect a world. `/seam connect` writes the config and starts the reporter live;
 * `/seam status` says whether it is working, which is the first thing anyone asks.
 *
 * **Owners only** — the server console, or an operator at the highest level. The command handles a
 * live credential, so it is not something a regular player gets to run.
 *
 * ⚠ 1.21.11 replaced integer permission levels with a predicate system: `hasPermissionLevel(int)`
 * is gone from `ServerCommandSource`, and the equivalent is one of `CommandManager`'s ready-made
 * `PermissionCheck` constants asked about the source's own `PermissionPredicate`. The old
 * `hasPermissionLevel(4)` is `OWNERS_CHECK`. See `docs/fabric-1.21.11-reference.md` §10.
 */
object SeamCommand {

    fun register(log: Logger) {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                CommandManager.literal("seam")
                    .requires { CommandManager.OWNERS_CHECK.allows(it.permissions) }
                    .then(CommandManager.literal("status").executes { status(it) })
                    .then(CommandManager.literal("disconnect").executes { disconnect(it, log) })
                    .then(
                        CommandManager.literal("connect")
                            .then(
                                CommandManager.argument("world_id", IntegerArgumentType.integer(1))
                                    .then(
                                        CommandManager.argument("token", StringArgumentType.string())
                                            .executes { connect(it, log, baseUrl = null) }
                                            .then(
                                                // greedyString, not string: Brigadier's unquoted
                                                // word is [A-Za-z0-9_.+-], so a URL's ":" and "/"
                                                // make `string()` fail with "expected whitespace to
                                                // end one argument". Greedy takes the rest of the
                                                // line, which is right for a trailing argument and
                                                // means the operator can type the URL bare.
                                                CommandManager.argument("api_base_url", StringArgumentType.greedyString())
                                                    .executes {
                                                        connect(
                                                            it,
                                                            log,
                                                            baseUrl = StringArgumentType.getString(it, "api_base_url"),
                                                        )
                                                    },
                                            ),
                                    ),
                            ),
                    ),
            )
        }
    }

    private fun status(ctx: CommandContext<ServerCommandSource>): Int {
        val config = Reporter.config
        if (config == null || !Reporter.isRunning) {
            ctx.source.sendFeedback({ Text.literal("Seam reporter: off") }, false)
            ctx.source.sendFeedback(
                { Text.literal("  Turn it on with /seam connect <world_id> <token>") },
                false,
            )
            ctx.source.sendFeedback(
                { Text.literal("  Get a token from the webapp: world settings → Connected server") },
                false,
            )
            return 0
        }
        ctx.source.sendFeedback({ Text.literal("Seam reporter: on") }, false)
        ctx.source.sendFeedback({ Text.literal("  World:    ${config.seamWorldId}") }, false)
        ctx.source.sendFeedback({ Text.literal("  Webapp:   ${config.apiBaseUrl}") }, false)
        ctx.source.sendFeedback(
            { Text.literal("  Sweeping: every ${config.sweepSeconds}s, ${config.readsPerTick} containers per tick") },
            false,
        )

        // "on" is not the same as "working", and the gap between them is the whole reason anyone
        // types this command. Say what it has actually done.
        val status = Reporter.status()
        if (status == null) {
            ctx.source.sendFeedback({ Text.literal("  It has not ticked yet.") }, false)
            return 1
        }
        ctx.source.sendFeedback(
            { Text.literal("  Tagged:   ${status.taggedContainers} container(s) in ${status.groups} inventory group(s)") },
            false,
        )
        ctx.source.sendFeedback(
            {
                Text.literal(
                    "  Swept:    " + (status.lastPassEndedAt?.let { "last finished $it" } ?: "no pass finished yet"),
                )
            },
            false,
        )
        ctx.source.sendFeedback(
            {
                Text.literal(
                    "  Pushed:   " + (
                        status.lastPushAt?.let { "$it (${status.lastPushContainers} container(s) changed)" }
                            ?: "nothing accepted yet"
                        ),
                )
            },
            false,
        )
        if (status.taggedContainers == 0) {
            ctx.source.sendFeedback(
                { Text.literal("  No containers are tagged yet, so there is nothing to measure.") },
                false,
            )
        }
        if (status.projectsWithoutItemsOfInterest.isNotEmpty()) {
            ctx.source.sendFeedback(
                {
                    Text.literal(
                        "  Project(s) ${status.projectsWithoutItemsOfInterest.joinToString(", ")} have no target " +
                            "or plan items, so their tagged containers report nothing.",
                    )
                },
                false,
            )
        }
        val error = status.lastError
        if (error != null) ctx.source.sendError(Text.literal("  Last problem: $error"))
        return 1
    }

    private fun connect(ctx: CommandContext<ServerCommandSource>, log: Logger, baseUrl: String?): Int {
        val worldId = IntegerArgumentType.getInteger(ctx, "world_id")
        val token = StringArgumentType.getString(ctx, "token")

        val config = ReporterConfig(
            apiBaseUrl = baseUrl?.trimEnd('/') ?: Reporter.config?.apiBaseUrl ?: ReporterConfig.defaultBaseUrl(),
            seamWorldId = worldId,
            token = token,
        ).sanitised()

        val saved = ReporterConfig.save(config)
        if (saved.isFailure) {
            // Do not surface the exception message: it can quote the path and, on some filesystems,
            // the content being written — which is the token.
            val kind = saved.exceptionOrNull()?.javaClass?.simpleName ?: "error"
            ctx.source.sendError(Text.literal("Could not write ${ReporterConfig.path()} ($kind)"))
            return 0
        }

        if (!Reporter.start(config, log)) {
            ctx.source.sendError(Text.literal("Config saved, but it is not usable — check the world id and token"))
            return 0
        }

        ctx.source.sendFeedback(
            { Text.literal("Seam reporter connected to world $worldId at ${config.apiBaseUrl}") },
            true,
        )
        ctx.source.sendFeedback(
            { Text.literal("  Saved to ${ReporterConfig.path()} — it will start automatically from now on.") },
            false,
        )
        // Minecraft logs commands players run. Said plainly, because the fix (rotate the token in
        // world settings) is easy and only obvious if someone tells you.
        if (ctx.source.entity != null) {
            ctx.source.sendFeedback(
                {
                    Text.literal(
                        "  Note: you ran this in-game, so the token is now in the server log. " +
                            "Prefer the server console, or revoke and re-mint the token if that matters.",
                    )
                },
                false,
            )
        }
        return 1
    }

    private fun disconnect(ctx: CommandContext<ServerCommandSource>, log: Logger): Int {
        if (!Reporter.isRunning) {
            ctx.source.sendFeedback({ Text.literal("Seam reporter is already off") }, false)
            return 0
        }
        Reporter.stop()
        log.info("Seam reporter stopped by command")
        ctx.source.sendFeedback({ Text.literal("Seam reporter stopped") }, true)
        ctx.source.sendFeedback(
            {
                Text.literal(
                    "  ${ReporterConfig.path()} still holds the token, so it will start again on restart. " +
                        "Delete the file, or revoke the token in world settings, to stop that.",
                )
            },
            false,
        )
        return 1
    }
}
