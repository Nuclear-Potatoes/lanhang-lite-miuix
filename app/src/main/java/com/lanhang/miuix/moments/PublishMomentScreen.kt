package com.lanhang.miuix.moments

import android.net.Uri
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.lanhang.miuix.R
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 朋友圈式发表页最多 9 张图（与后端 `MomentsApi.publishFeed` 的 media 列表一致）。 */
const val MAX_PUBLISH_IMAGES = 9

/**
 * 发表动态页（整页，不再是弹窗）。
 *
 * 参考微信朋友圈的「这一刻的想法 + 九宫格选图」，但外观全部换成 Miuix：
 * 两张 `Card`（正文 / 图片），九宫格用等宽方格 + 12dp 圆角，
 * 末尾固定一个「＋」格子，选满 9 张自动消失。
 *
 * 发表动作不在这里——它在顶栏右上角的 `TextButton`（见 `ShellBars.LanhangTopBar`），
 * 与 Miuix 的二级页顶栏一致；按钮的可用状态由宿主决定。
 */
@Composable
fun PublishMomentScreen(
    text: String,
    onTextChange: (String) -> Unit,
    images: List<Uri>,
    onPickImages: () -> Unit,
    onRemoveImage: (Int) -> Unit,
    onPreviewImage: (Int) -> Unit,
    /** 长按拖拽排序：把第 from 张移到第 to 张（宿主直接改 `pickedImages`） */
    onMoveImage: (Int, Int) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {

        // ---- 正文 ----
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(16.dp),
        ) {
            Column {
                TextField(
                    value = text,
                    onValueChange = onTextChange,
                    label = stringResource(R.string.moments_publish_hint),
                    minLines = 5,
                    maxLines = 14,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        text = "${text.length}/2000",
                        style = MiuixTheme.textStyles.footnote2,
                        color = scheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        // ---- 图片九宫格 ----
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(16.dp),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.moments_publish_images),
                        style = MiuixTheme.textStyles.body1,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${images.size}/$MAX_PUBLISH_IMAGES",
                        style = MiuixTheme.textStyles.footnote2,
                        color = scheme.onSurfaceVariantSummary,
                    )
                }
                Spacer(Modifier.height(12.dp))

                ImageGrid(
                    images = images,
                    onRemoveImage = onRemoveImage,
                    onPreviewImage = onPreviewImage,
                    onPickImages = onPickImages,
                    onMoveImage = onMoveImage,
                )
                if (images.size > 1) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.moments_publish_reorder_hint),
                        style = MiuixTheme.textStyles.footnote2,
                        color = scheme.onSurfaceVariantSummary,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.moments_publish_tip),
            style = MiuixTheme.textStyles.footnote2,
            color = scheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 九宫格：3 列等宽方格 + 末尾「＋」格子，**长按图片可以拖动排序**。
 *
 * 为什么不用 `LazyVerticalGrid.animateItem()`：这一格嵌在一整页 `verticalScroll` 里，
 * 懒列高度要自己算、还要处理嵌套滑动；而最多只有 9 张图，所以直接做**绝对定位 + 弹簧**：
 * 每张图按「它在列表里的槽位」算出左上角像素坐标，槽位一变就 `animateFloatAsState` 弹过去；
 * 正在拖的那张走 `snap()`（手指到哪它在哪），松手时槽位已更新，它自己弹回格子里。
 *
 * 移动判定按「拖过了几个格子」算（`拖动位移 / 格距` 四舍五入），跨格立刻把数据列表换位，
 * 所以其他图会当帧开始让位动画，手感跟系统相册/微信一致。
 */
@Composable
private fun ImageGrid(
    images: List<Uri>,
    onRemoveImage: (Int) -> Unit,
    onPreviewImage: (Int) -> Unit,
    onPickImages: () -> Unit,
    onMoveImage: (Int, Int) -> Unit,
) {
    val gap = 8.dp
    val haptic = LocalHapticFeedback.current
    // 手势协程会活很久，images/onMoveImage 必须取最新值，否则换位后拿到的是旧列表
    val imagesState = rememberUpdatedState(images)
    val moveState = rememberUpdatedState(onMoveImage)
    var dragIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val gapPx = with(density) { gap.toPx() }
        val cellPx = (constraints.maxWidth - 2 * gapPx) / 3f
        val step = cellPx + gapPx
        val tiles = images.size + if (images.size < MAX_PUBLISH_IMAGES) 1 else 0
        val rows = (tiles + 2) / 3
        val cellDp = with(density) { cellPx.toDp() }
        val gridHeight = with(density) {
            (rows * cellPx + (rows - 1).coerceAtLeast(0) * gapPx).toDp()
        }

        Box(modifier = Modifier.fillMaxWidth().height(gridHeight)) {
            images.forEachIndexed { index, uri ->
                key(uri) {
                    val dragging = dragIndex == index
                    val slotX = (index % 3) * step
                    val slotY = (index / 3) * step
                    // 拖动中的那张不插值（snap），其余用弹簧让位
                    val spec: androidx.compose.animation.core.AnimationSpec<Float> =
                        if (dragging) snap()
                        else spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
                    val x by animateFloatAsState(
                        targetValue = if (dragging) slotX + dragOffset.x else slotX,
                        animationSpec = spec,
                        label = "tileX",
                    )
                    val y by animateFloatAsState(
                        targetValue = if (dragging) slotY + dragOffset.y else slotY,
                        animationSpec = spec,
                        label = "tileY",
                    )
                    // 拿起来的缩放 + 阴影：拖动时浮起，松手回弹
                    val liftScale by animateFloatAsState(
                        targetValue = if (dragging) 1.07f else 1f,
                        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium),
                        label = "tileScale",
                    )
                    val liftElevation by animateDpAsState(
                        targetValue = if (dragging) 14.dp else 0.dp,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                        label = "tileLift",
                    )
                    val shape = RoundedCornerShape(12.dp)

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                            .size(cellDp)
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                scaleX = liftScale
                                scaleY = liftScale
                                shadowElevation = with(density) { liftElevation.toPx() }
                                this.shape = shape
                                clip = false
                            }
                            .pointerInput(uri) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        val from = imagesState.value.indexOf(uri)
                                        if (from >= 0) {
                                            dragIndex = from
                                            dragOffset = Offset.Zero
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        val from = dragIndex
                                        if (from >= 0) {
                                            dragOffset += amount
                                            val last = (imagesState.value.size - 1).coerceAtLeast(0)
                                            val startCol = from % 3
                                            val startRow = from / 3
                                            val targetCol = (startCol + (dragOffset.x / step).roundToInt())
                                                .coerceIn(0, 2)
                                            val targetRow = (startRow + (dragOffset.y / step).roundToInt())
                                                .coerceAtLeast(0)
                                            val target = (targetRow * 3 + targetCol).coerceIn(0, last)
                                            if (target != from) {
                                                moveState.value(from, target)
                                                dragIndex = target
                                                // 换位后把基准挪到新槽位，图片才不会从手指下跳走
                                                dragOffset -= Offset(
                                                    x = (targetCol - startCol) * step,
                                                    y = (targetRow - startRow) * step,
                                                )
                                            }
                                        }
                                    },
                                    onDragEnd = {
                                        dragIndex = -1
                                        dragOffset = Offset.Zero
                                    },
                                    onDragCancel = {
                                        dragIndex = -1
                                        dragOffset = Offset.Zero
                                    },
                                )
                            },
                    ) {
                        ImageTile(
                            uri = uri,
                            dragging = dragging,
                            onRemove = { index.takeIf { dragIndex < 0 }?.let(onRemoveImage) },
                            onPreview = {
                                val current = imagesState.value.indexOf(uri)
                                if (dragIndex < 0 && current >= 0) onPreviewImage(current)
                            },
                        )
                    }
                }
            }

            // 「＋」格子：跟着最后一张图一起让位
            if (images.size < MAX_PUBLISH_IMAGES) {
                key("add-tile") {
                    val addIndex = images.size
                    val draggingAdd = false
                    val slotX = (addIndex % 3) * step
                    val slotY = (addIndex / 3) * step
                    val x by animateFloatAsState(
                        targetValue = slotX,
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                        label = "addX",
                    )
                    val y by animateFloatAsState(
                        targetValue = slotY,
                        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                        label = "addY",
                    )
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                            .size(cellDp)
                            .zIndex(if (draggingAdd) 1f else 0f),
                    ) {
                        AddTile(onClick = onPickImages)
                    }
                }
            }
        }
    }
}

/** 一张已选图片：等比方格 + 右上角删除按钮（拖动时不显示删除键，免得挡住手指）。 */
@Composable
private fun ImageTile(
    uri: Uri,
    dragging: Boolean,
    onRemove: () -> Unit,
    onPreview: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(uri) {
                    // 长按已经交给外层做拖拽了，这里只保留单击预览
                    detectTapGestures(onTap = { onPreview() })
                },
        )
        if (!dragging) {
            Icon(
                imageVector = MiuixIcons.Close,
                contentDescription = stringResource(R.string.moments_publish_remove_image),
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .clickable(onClick = onRemove)
                    .padding(3.dp),
            )
        }
    }
}

/** 「＋」格子：点开系统相册（Photo Picker，多选最多 9 张）。 */
@Composable
private fun AddTile(onClick: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MiuixIcons.Add,
            contentDescription = stringResource(R.string.moments_publish_add_image),
            tint = scheme.onSurfaceVariantSummary,
            modifier = Modifier.size(26.dp),
        )
    }
}
