package edu.xaut.xilicheckin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import edu.xaut.xilicheckin.ui.AppRoot
import edu.xaut.xilicheckin.ui.theme.XiliTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XiliTheme {
                AppRoot()
            }
        }
    }
}
