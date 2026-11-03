package com.github.avbox.osc.util

/**
 * 宿主级 hidden API 兼容：在进程最早期放行反射，供自行取 Activity 弹窗的 py 子程序使用。
 *
 * 我们 targetSdk 37，hidden API 限制比参照项目（targetSdk 28）严格得多。部分 py 工具脚本
 * 不用宿主注入的 builtins.getActivity()，而是自己 Class.forName("android.app.ActivityThread")
 * 反射 mActivities 取当前 Activity；反射被拒时脚本会直接「已取消本次弹窗」—— 弹窗根本
 * 不会被创建。这里在 Application.onCreate 第一行就放行，并自带一次探测验证是否真的生效。
 */
object HiddenApiCompat {

    private const val TAG = "HiddenApiCompat"

    @Volatile
    private var applied = false

    /** 探测结果写进日志，真机可据此区分「豁免没生效」和「脚本另有判断」。 */
    @Volatile
    var verified: Boolean = false
        private set

    @JvmStatic
    fun apply() {
        if (applied) return
        applied = true
        // 全放行：宿主自己就是运行容器，不再逐类枚举（枚举漏一个子类就前功尽弃）
        if (tryExempt(arrayOf("L"))) return
        // 部分 ROM 挡住 getDeclaredMethod，退回到细分前缀
        tryExempt(
            arrayOf(
                "Landroid/app/ActivityThread;",
                "Landroid/app/ActivityThread\$",
                "Landroid/app/Activity;",
                "Landroid/app/Application;",
                "Landroid/app/ContextImpl;",
                "Landroid/app/LoadedApk;",
                "Landroid/app/Instrumentation;",
                "Landroid/content/ContextWrapper;",
                "Landroid/view/View;",
                "Landroid/view/Window;",
                "Ldalvik/system/VMRuntime;",
                "Ljava/lang/Class;",
                "Ljava/lang/reflect/Method;",
                "Ljava/lang/reflect/Field;",
            ),
        )
    }

    private fun tryExempt(prefixes: Array<String>): Boolean = try {
        val vm = Class.forName("dalvik.system.VMRuntime")
        val runtime = vm.getDeclaredMethod("getRuntime").apply { isAccessible = true }
            .invoke(null) ?: return false
        val set = vm.getDeclaredMethod("setHiddenApiExemptions", Array<String>::class.java)
            .apply { isAccessible = true }
        set.invoke(runtime, prefixes as Any)
        verified = probe()
        AppLog.i("$TAG: exemption applied prefixes=${prefixes.size} verified=$verified")
        true
    } catch (th: Throwable) {
        AppLog.i("$TAG: exemption unavailable: ${th.message}")
        false
    }

    private fun probe(): Boolean = try {
        val c = Class.forName("android.app.ActivityThread")
        c.getDeclaredField("mActivities")
        true
    } catch (th: Throwable) {
        false
    }
}
