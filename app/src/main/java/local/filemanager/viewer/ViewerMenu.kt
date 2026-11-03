package local.filemanager

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * プレビューの右上メニューの中身。どのファイルでも「開く・共有」が一番上、「表示」が真ん中、「ファイル」が一番下、と
 * 場所を固定する。ファイルの種類によって変わるのは、各まとまりの中の項目だけ。
 */
internal sealed interface VMenuItem {
    /** 押すと実行してメニューを閉じる */
    class Action(val icon: ImageVector, val label: String, val onClick: () -> Unit) : VMenuItem

    /** オン・オフ。押すとメニューを閉じる */
    class Toggle(val icon: ImageVector, val label: String, val checked: Boolean, val onChange: (Boolean) -> Unit) : VMenuItem

    /** 二つ以上から一つを選ぶ(表示のしかたの切り替え)。選ぶとメニューを閉じる */
    class Segment(val label: String, val options: List<String>, val selected: Int, val onSelect: (Int) -> Unit) : VMenuItem

    /** 文字の大きさ。何度も押せるよう、メニューは閉じない */
    class TextSize(val sp: Int, val min: Int, val max: Int, val onChange: (Int) -> Unit) : VMenuItem

    /** 選択肢が多い・名前が長いときの一覧(音声や字幕の切り替え)。選んでいるものにチェックを付ける。選ぶとメニューを閉じる */
    class Choice(val icon: ImageVector, val label: String, val options: List<String>, val selected: Int, val onSelect: (Int) -> Unit) : VMenuItem
}

internal class VMenuSection(val title: String, val items: List<VMenuItem>)

private class MenuColors(dark: Boolean) {
    val surface = if (dark) Color(0xFF263238) else Color(0xFFFFFFFF)
    val text = if (dark) Color(0xFFECEFF1) else Color(0xFF1F2A30)
    val sub = if (dark) Color(0xFF9FB0B8) else Color(0xFF5F6F78)
    val accent = if (dark) Color(0xFF9FA8DA) else Color(0xFF3949AB)
    val onAccent = if (dark) Color(0xFF141A52) else Color.White
    val tile = accent.copy(alpha = if (dark) 0.20f else 0.12f)
    val track = if (dark) Color(0xFF37474F) else Color(0xFFE9EFF1)
    val divider = if (dark) Color(0x33FFFFFF) else Color(0x1F000000)
}

@Composable
internal fun ViewerMenu(
    expanded: Boolean,
    dark: Boolean,
    onDismiss: () -> Unit,
    onOpenExternal: () -> Unit,
    onShare: () -> Unit,
    sections: List<VMenuSection>,
    /** false なら、いちばん上の「他のアプリで開く・共有」を出さない(編集中は、保存前の中身を渡さないため) */
    tiles: Boolean = true,
    /** true なら、押したボタンの上に出す(画面の下のバーから開くメニュー用)。false は下に出す */
    above: Boolean = false,
    /** 項目が少ない小さなメニューは、幅を狭くする */
    width: Dp = 300.dp,
) {
    if (!expanded) return
    val c = MenuColors(dark)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    val density = LocalDensity.current
    val enter = remember { MutableTransitionState(false).apply { targetState = true } }
    val position = remember(above, density) { MenuPosition(above, with(density) { 52.dp.roundToPx() }) }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        AnimatedVisibility(
            visibleState = enter,
            enter = fadeIn(tween(120)) + scaleIn(tween(160), initialScale = 0.85f, transformOrigin = TransformOrigin(1f, if (above) 1f else 0f)),
        ) {
            Surface(
                modifier = Modifier.padding(horizontal = 10.dp).width(width).heightIn(max = maxH),
                shape = RoundedCornerShape(12.dp),
                color = c.surface,
                shadowElevation = 4.dp,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    // いちばん上: どのファイルでも同じ場所
                    if (tiles) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        QuickTile(Icons.Default.OpenInNew, "他のアプリで開く", c, Modifier.weight(1f)) { onDismiss(); onOpenExternal() }
                        QuickTile(Icons.Default.Share, "共有", c, Modifier.weight(1f)) { onDismiss(); onShare() }
                    }
                    var first = !tiles
                    for (s in sections) {
                        if (s.items.isEmpty()) continue
                        if (!first) {
                            Spacer(Modifier.height(12.dp))
                            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                        }
                        if (s.title.isNotEmpty()) Text(
                            s.title, color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                            modifier = Modifier.padding(top = if (first) 2.dp else 12.dp, bottom = 6.dp, start = 2.dp),
                        )
                        first = false
                        for (item in s.items) {
                            when (item) {
                                is VMenuItem.Action -> Row1(item.icon, item.label, c, onClick = { onDismiss(); item.onClick() }) {}
                                is VMenuItem.Toggle -> Row1(item.icon, item.label, c, onClick = { onDismiss(); item.onChange(!item.checked) }) {
                                    Switch(
                                        checked = item.checked, onCheckedChange = null,
                                        modifier = Modifier.scale(0.85f),
                                        colors = SwitchDefaults.colors(
                                            checkedTrackColor = c.accent, checkedThumbColor = c.onAccent,
                                            uncheckedTrackColor = c.track, uncheckedThumbColor = c.sub, uncheckedBorderColor = c.sub,
                                        ),
                                    )
                                }
                                is VMenuItem.Segment -> SegmentRow(item, c, onDismiss)
                                is VMenuItem.TextSize -> TextSizeRow(item, c)
                                is VMenuItem.Choice -> ChoiceRows(item, c, onDismiss)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ボタンの右端にそろえて、下(above なら上)に出す。画面からはみ出さないよう、中に収める。 */
private class MenuPosition(private val above: Boolean, private val dropPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width).coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = if (above) anchorBounds.top - popupContentSize.height else anchorBounds.top + dropPx
        return IntOffset(x, y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)))
    }
}

/**
 * 項目が少ない一覧のメニュー(行の「︙」や選択中の「その他」)。見た目はプレビューの右上メニューと同じ。
 * 「他のアプリで開く・共有」は出さず、押すと実行して閉じる項目だけを並べる。
 */
@Composable
internal fun ListMenu(expanded: Boolean, dark: Boolean, onDismiss: () -> Unit, items: List<VMenuItem.Action>, above: Boolean = false) {
    ViewerMenu(
        expanded = expanded, dark = dark, onDismiss = onDismiss, onOpenExternal = {}, onShare = {},
        sections = listOf(VMenuSection("", items)), tiles = false, above = above, width = 240.dp,
    )
}
@Composable
private fun QuickTile(icon: ImageVector, label: String, c: MenuColors, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(c.tile).clickable(onClick = onClick).heightIn(min = 72.dp).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = c.text, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** 左に色つきの四角の中の絵、真ん中に名前、右に(あれば)スイッチ。高さは 52dp で全部そろえる。 */
@Composable
private fun Row1(icon: ImageVector, label: String, c: MenuColors, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, c)
        Spacer(Modifier.width(12.dp))
        Text(label, color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        trailing()
    }
}

@Composable
private fun IconTile(icon: ImageVector, c: MenuColors) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)).background(c.tile), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SegmentRow(s: VMenuItem.Segment, c: MenuColors, dismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(s.label, color = c.sub, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(c.track).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            s.options.forEachIndexed { i, name ->
                val on = i == s.selected
                val bg by animateColorAsState(if (on) c.accent else Color.Transparent, label = "seg")
                Box(
                    Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(6.dp)).background(bg)
                        .clickable { if (!on) { dismiss(); s.onSelect(i) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name, color = if (on) c.onAccent else c.text, fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }
    }
}

/** 見出し(絵+名前)の下に、選択肢を縦に並べる。選んでいるものは色を変えてチェックを付ける。 */
@Composable
private fun ChoiceRows(ch: VMenuItem.Choice, c: MenuColors, dismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(ch.icon, c)
            Spacer(Modifier.width(12.dp))
            Text(ch.label, color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ch.options.forEachIndexed { i, name ->
            val on = i == ch.selected
            Row(
                Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                    .clickable { dismiss(); if (!on) ch.onSelect(i) }
                    .padding(start = 50.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    name, color = if (on) c.accent else c.text, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (on) Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun TextSizeRow(t: VMenuItem.TextSize, c: MenuColors) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 2.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconTile(Icons.Default.TextFields, c)
        Spacer(Modifier.width(12.dp))
        Text("文字の大きさ", color = c.text, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1)
        StepButton(Icons.Default.Remove, "小さく", t.sp > t.min, c) { t.onChange((t.sp - 2).coerceAtLeast(t.min)) }
        Text("${t.sp}", color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(34.dp))
        StepButton(Icons.Default.Add, "大きく", t.sp < t.max, c) { t.onChange((t.sp + 2).coerceAtMost(t.max)) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, desc: String, enabled: Boolean, c: MenuColors, onClick: () -> Unit) {
    val tint = if (enabled) c.accent else c.sub.copy(alpha = 0.4f)
    Box(
        Modifier.size(40.dp).clip(CircleShape).border(1.5.dp, tint, CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = tint, modifier = Modifier.size(20.dp)) }
}
