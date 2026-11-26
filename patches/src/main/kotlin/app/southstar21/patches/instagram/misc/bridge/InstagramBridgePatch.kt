package app.southstar21.patches.instagram.misc.bridge

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.southstar21.patches.instagram.misc.extension.sharedExtensionPatch
import app.southstar21.patches.instagram.shared.Constants.COMPATIBILITY_INSTAGRAM
import app.southstar21.patches.instagram.shared.MainActivityWindowFocusFingerprint

private const val BRIDGE_CLASS = "Lapp/southstar21/extension/instagram/bridge/InstagramBridge;"

internal object BearerTokenCheckFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf("Bearer IGT:2:"),
)

@Suppress("unused")
val instagramBridgePatch = bytecodePatch(
    name = "Instagram bridge (localhost)",
    description = "Starts a local 127.0.0.1:48913 HTTP bridge inside Instagram for follow, post like, and reel like.",
) {
    compatibleWith(COMPATIBILITY_INSTAGRAM)
    dependsOn(sharedExtensionPatch)

    execute {
        MainActivityWindowFocusFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $BRIDGE_CLASS->install(Landroid/app/Activity;)V",
        )
        BearerTokenCheckFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $BRIDGE_CLASS->captureToken(Ljava/lang/String;)V",
        )
    }
}
