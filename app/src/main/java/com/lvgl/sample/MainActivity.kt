package com.lvgl.sample

import android.os.Bundle
import android.util.Log
import com.lvgl.android.activity.LvglActivity

class MainActivity : LvglActivity() {

    override fun onCreateLvgl() {
        Log.d(TAG, "onCreateLvgl")
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
