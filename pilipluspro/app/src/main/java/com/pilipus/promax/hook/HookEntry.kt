package com.pilipus.promax

import android.app.Activity
import android.media.MediaFormat
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.factory.encase
import com.highcapable.yukihookapi.hook.log.YLog
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import kotlin.math.abs

@InjectYukiHookWithXposed
object HookEntry : IYukiHookXposedInit {

    private var currentVideoFps: Float = 60f // 默认兜底帧率
    private var currentActivity: Activity? = null
    private var defaultHighModeId = 0 // 用于记录原本的 120Hz 模式 ID

    override fun onHook() = encase {

        // ⚠️ 极其重要：请再次确认 "com.example.piliplus" 是不是该软件真实的包名！
        loadApp(name = "com.example.piliplus") {

            // 1. 动态获取真实帧率：拦截安卓底层解码器的参数配置
            "android.media.MediaFormat".hook {
                injectMember {
                    method {
                        name = "setInteger"
                        param(String::class.java, Int::class.java)
                    }
                    afterHook {
                        val key = args[0] as? String
                        // 拦截 "frame-rate" 键值对，抓取视频原始帧率
                        if (key == MediaFormat.KEY_FRAME_RATE) {
                            currentVideoFps = (args[1] as Int).toFloat()
                            YLog.info("📊 抓取到视频真实帧率: $currentVideoFps fps")
                        }
                    }
                }
            }

            // 2. 获取当前页面，并记录系统的默认高刷 ID
            Activity::class.java.hook {
                injectMember {
                    method { name = "onResume" }
                    afterHook {
                        currentActivity = instance<Activity>()
                        val modes = currentActivity?.windowManager?.defaultDisplay?.supportedModes
                        // 找到屏幕支持的最高刷新率（比如 120Hz）的 Mode ID 保存下来，用于退出视频时恢复
                        defaultHighModeId = modes?.maxByOrNull { it.refreshRate }?.modeId ?: 0
                    }
                }
            }

            // 3. 视频开始与结束时的动态控制逻辑
            "com.alexmercerind.media_kit_video.VideoOutput".hook {
                // 拦截播放器组件的初始化
                injectMember {
                    constructor()
                    afterHook {
                        YLog.info("▶️ 视频开始渲染，启动动态帧率匹配算法...")
                        matchAndSetRefreshRate(currentVideoFps)
                    }
                }

                // 拦截播放器组件的销毁
                injectMember {
                    method { name = "release" }
                    afterHook {
                        if (defaultHighModeId > 0) {
                            YLog.info("⏹️ 视频退出，恢复全局最高刷新率 (120Hz)")
                            setRefreshMode(defaultHighModeId)
                        }
                    }
                }
            }
        }
    }

    /**
     * 动态帧率匹配算法：
     * 根据抓取到的视频 FPS，在手机支持的刷新率档位中找一个最接近且能覆盖的档位
     */
    private fun matchAndSetRefreshRate(videoFps: Float) {
        val display = currentActivity?.windowManager?.defaultDisplay ?: return
        val modes = display.supportedModes ?: return

        // 对于没有 LTPO 的屏幕，支持的最低档位通常是 60Hz。
        // 无论视频是 24, 30 还是 60fps，我们都向下匹配到 60Hz 档位来达到最大省电目的。
        // 如果遇到 90 或 120fps 的超高帧率视频，则赋予更高的目标值。
        val targetHz = if (videoFps <= 60f) 60f else videoFps

        var bestModeId = defaultHighModeId
        var minDiff = Float.MAX_VALUE

        // 遍历手机屏幕支持的所有档位，计算差值，找出最契合 targetHz 的那一个档位
        for (mode in modes) {
            val fpsDiff = abs(mode.refreshRate - targetHz)
            if (fpsDiff < minDiff) {
                minDiff = fpsDiff
                bestModeId = mode.modeId
            }
        }

        if (bestModeId > 0) {
            YLog.info("🔄 匹配成功：视频 ${videoFps}fps -> 屏幕锁定至 Mode ID: $bestModeId")
            setRefreshMode(bestModeId)
        }
    }

    // 核心执行方法：通知系统底层修改屏幕的刷新率属性
    private fun setRefreshMode(modeId: Int) {
        currentActivity?.runOnUiThread {
            try {
                val window = currentActivity?.window ?: return@runOnUiThread
                val layoutParams = window.attributes
                layoutParams.preferredDisplayModeId = modeId
                window.attributes = layoutParams
            } catch (e: Exception) {
                YLog.error("切换刷新率异常", e)
            }
        }
    }
}