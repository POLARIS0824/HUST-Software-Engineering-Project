@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.example.looma.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 避免 300ms 内完成的小操作闪现进度动画，同时为 Indicator 留固定空间 */
@Composable
fun DelayedLoadingIndicator(loading: Boolean, modifier: Modifier = Modifier) {
    var ready by remember(loading) { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            delay(300L)
            ready = true
        }
    }
    val motion = MaterialTheme.motionScheme
    Box(modifier.size(20.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = loading && ready,
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
        ) {
            LoadingIndicator(
                modifier = Modifier.size(20.dp),
                color = LocalContentColor.current,
            )
        }
    }
}
