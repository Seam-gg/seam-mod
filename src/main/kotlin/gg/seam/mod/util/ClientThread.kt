package gg.seam.mod.util

import net.minecraft.client.MinecraftClient
import java.util.concurrent.CompletableFuture

/**
 * Marshalling back onto Minecraft's client thread.
 *
 * API calls, JSON parsing and disk I/O all run off-thread; anything that reads or mutates game
 * state — screens, chat, the world — must happen on the client thread. `MinecraftClient` is a
 * `ReentrantThreadExecutor`, so `execute {}` queues onto it (running inline if we're already there).
 * See docs/fabric-1.21.11-reference.md §7.
 */

/** Run [block] on the client thread. */
fun onClientThread(block: () -> Unit) {
    MinecraftClient.getInstance().execute(block)
}

/**
 * Consume this future's value on the client thread. Both completion paths land there, so callers
 * never have to think about which thread the continuation runs on.
 */
fun <T> CompletableFuture<T>.thenOnClientThread(action: (T) -> Unit): CompletableFuture<Void> =
    thenAccept { value -> onClientThread { action(value) } }
