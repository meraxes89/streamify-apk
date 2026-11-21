package com.streamify.app.ui.components.yt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamify.app.ui.theme.*

private data class NavItemData(
    val route: String,
    val label: String,
    val activeIcon: ImageVector,
    val inactiveIcon: ImageVector
)

private val NAV_ITEMS = listOf(
    NavItemData("home", "Home", Icons.Filled.Home, Icons.Outlined.Home),
    NavItemData("search", "Explore", Icons.Filled.Explore, Icons.Outlined.Explore),
    NavItemData("library", "Library", Icons.Filled.LibraryMusic, Icons.Outlined.LibraryMusic),
    NavItemData("downloads", "Downloads", Icons.Filled.Download, Icons.Outlined.Download)
)

@Composable
fun YtBottomNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    alpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    val selectedIndex = remember(currentRoute) {
        when (currentRoute) {
            "search", "explore" -> 1
            "library" -> 2
            "downloads" -> 3
            else -> 0
        }
    }

    Surface(
        color = BgBase,
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer { this.alpha = alpha }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Hairline top border
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BorderSubtle)
            )

            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val tabCount = NAV_ITEMS.size
                val tabWidth = maxWidth / tabCount
                val activePillWidth = (tabWidth - 16.dp).coerceAtLeast(40.dp)

                // 120 FPS Fluid Spring-Physics Pill Slider
                val animatedPillOffset by animateDpAsState(
                    targetValue = (tabWidth * selectedIndex) + 8.dp,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "bottomNavPillOffset"
                )

                // Sliding Spring Background Glow Pill
                Box(
                    modifier = Modifier
                        .offset(x = animatedPillOffset)
                        .width(activePillWidth)
                        .fillMaxHeight()
                        .padding(vertical = 6.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(BgSurfaceElevated.copy(alpha = 0.85f))
                )

                // Tabs Row
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NAV_ITEMS.forEachIndexed { index, item ->
                        val isSelected = selectedIndex == index
                        YtFluidNavItem(
                            item = item,
                            isSelected = isSelected,
                            onClick = { onNavigate(item.route) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun YtFluidNavItem(
    item: NavItemData,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    // Smooth Color Transitions
    val tintColor by animateColorAsState(
        targetValue = if (isSelected) TextMain else TextSecondary,
        animationSpec = tween(220),
        label = "navItemTintColor"
    )

    // Micro-scale spring pop on selection
    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.12f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "navItemScale"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (!isSelected) {
                        com.streamify.app.util.StreamifyHapticEngine.magneticDetent()
                    }
                    onClick()
                }
            )
            .padding(vertical = 4.dp)
    ) {
        Icon(
            imageVector = if (isSelected) item.activeIcon else item.inactiveIcon,
            contentDescription = item.label,
            tint = tintColor,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                }
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = item.label,
            style = LocalAppTypography.current.chipText.copy(
                fontSize = 10.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            ),
            color = tintColor
        )
    }
}
