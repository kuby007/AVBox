package com.github.avbox.osc.util

import android.app.Activity

import java.util.Stack

class ActivityStack private constructor() {

    fun addActivity(activity: Activity) {
        val stack = activityStack ?: Stack<Activity>()
        activityStack = stack
        stack.add(activity)
    }

    fun isActivity(): Boolean {
        return activityStack?.isEmpty() == false
    }

    /** 供宿主判定「是否还有播放页在前端」使用，返回当前 Activity 快照。 */
    fun activities(): List<Activity> {
        return activityStack?.toList() ?: emptyList()
    }

    fun currentActivity(): Activity {
        return requireNotNull(activityStack) { "activity stack not initialized" }.lastElement()
    }

    fun finishActivity() {
        val activity = requireNotNull(activityStack) { "activity stack not initialized" }.lastElement()
        if (!activity.isFinishing()) {
            activity.finish()
        }
    }

    fun finishActivity(activity: Activity) {
        activityStack?.remove(activity)
    }

    fun finishActivity(cls: Class<*>) {
        for (activity in activityStack.orEmpty()) {
            if (activity.javaClass.equals(cls)) {
                if (!activity.isFinishing()) {
                    activity.finish()
                }
                break
            }
        }
    }

    fun backActivity(cls: Class<*>) {
        val stack = activityStack ?: return
        while (!stack.empty()) {
            val activity = stack.pop()
            if (activity.javaClass.equals(cls)) {
                stack.push(activity)
                break
            } else {
                activity.finish()
            }
        }
    }

    fun finishAllActivity() {
        val stack = activityStack
        if (stack != null && stack.size > 0) {
            for (i in 0 until stack.size) {
                val activity = stack[i]
                if (null != activity) {
                    if (!activity.isFinishing()) {
                        activity.finish()
                    }
                }
            }
            stack.clear()
        }
    }

    fun getActivity(cls: Class<*>): Activity? {
        for (activity in activityStack.orEmpty()) {
            if (activity.javaClass.equals(cls)) {
                return activity
            }
        }
        return null
    }

    fun appExit(code: Int) {
        try {
            finishAllActivity()
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(code)
        } catch (e: Exception) {
            activityStack?.clear()
            AppLog.e("AppManager", e)
        }
    }

    companion object {
        private var activityStack: Stack<Activity>? = null

        private val instance = ActivityStack()

        @JvmStatic
        fun getInstance(): ActivityStack {
            return instance
        }
    }
}
