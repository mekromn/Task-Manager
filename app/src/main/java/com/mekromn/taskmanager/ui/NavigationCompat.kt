package com.mekromn.taskmanager.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * API-36-compatible bottom-navigation item.
 *
 * Material3's NavigationBarItem is a RowScope extension in the stable
 * API-36 Compose line. Keeping this as a plain composable lets the main
 * navigation code stay independent of that receiver detail while retaining
 * the same polished selected/unselected behavior.
 */
@Composable
internal fun NavigationBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit
) {
    val container by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            Color.Transparent
        },
        label = "bottomNavContainer"
    )
    val content by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        label = "bottomNavContent"
    )

    Column(
        modifier = Modifier
            .padding(horizontal = 3.dp, vertical = 7.dp)
            .width(84.dp)
            .height(62.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        androidx.compose.material3.Surface(
            color = container,
            contentColor = content,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.size(width = 54.dp, height = 30.dp)
        ) {
            androidx.compose.foundation.layout.Box(
                contentAlignment = Alignment.Center
            ) {
                icon()
            }
        }
        Spacer(Modifier.height(2.dp))
        androidx.compose.material3.ProvideTextStyle(
            value = MaterialTheme.typography.labelSmall.copy(color = content),
            content = label
        )
    }
}
