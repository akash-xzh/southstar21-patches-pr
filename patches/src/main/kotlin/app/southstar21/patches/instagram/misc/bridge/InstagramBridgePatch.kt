package app.southstar21.patches.instagram.misc.bridge

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.southstar21.patches.instagram.misc.extension.sharedExtensionPatch
import app.southstar21.patches.instagram.shared.Constants.COMPATIBILITY_INSTAGRAM
import app.southstar21.patches.instagram.shared.MainActivityWindowFocusFingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val BRIDGE_CLASS = "Lapp/southstar21/extension/instagram/bridge/InstagramBridge;"

internal object HeaderAttachCallerFingerprint : Fingerprint(
    strings = listOf("Authorization", "X-MID"),
)

internal object CapabilitiesHeaderCallerFingerprint : Fingerprint(
    strings = listOf("X-IG-Capabilities", "3brTv10C"),
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

        val caller = HeaderAttachCallerFingerprint.method
        val headerHelperRef = caller.instructions
            .asSequence()
            .filter { it.opcode == Opcode.INVOKE_STATIC }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .firstOrNull {
                it.returnType == "V" &&
                    it.parameterTypes == listOf("Ljava/util/List;", "Ljava/lang/String;", "Ljava/lang/String;")
            } ?: error("Could not find header attach helper in ${caller.definingClass}.${caller.name}")

        val helperClass = mutableClassDefBy(headerHelperRef.definingClass)
        val helperMethod = helperClass.methods.first {
            it.name == headerHelperRef.name &&
                it.parameterTypes == headerHelperRef.parameterTypes &&
                it.returnType == headerHelperRef.returnType
        }
        helperMethod.addInstruction(
            0,
            "invoke-static { p1, p2 }, $BRIDGE_CLASS->captureHeader(Ljava/lang/String;Ljava/lang/String;)V",
        )

        val capCaller = CapabilitiesHeaderCallerFingerprint.method
        val addHeaderRef = capCaller.instructions
            .asSequence()
            .filter { it.opcode == Opcode.INVOKE_VIRTUAL }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .firstOrNull {
                it.returnType == "V" &&
                    it.parameterTypes == listOf("Ljava/lang/String;", "Ljava/lang/String;")
            }
        if (addHeaderRef != null) {
            val reqBuilderClass = mutableClassDefBy(addHeaderRef.definingClass)
            val addHeaderMethod = reqBuilderClass.methods.first {
                it.name == addHeaderRef.name &&
                    it.parameterTypes == addHeaderRef.parameterTypes &&
                    it.returnType == addHeaderRef.returnType
            }
            addHeaderMethod.addInstruction(
                0,
                "invoke-static { p1, p2 }, $BRIDGE_CLASS->captureHeader(Ljava/lang/String;Ljava/lang/String;)V",
            )
        }
    }
}
