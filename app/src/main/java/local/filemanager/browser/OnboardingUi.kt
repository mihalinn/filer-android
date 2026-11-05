package local.filemanager

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * 初めて使うときの案内(4 つの段階)。言語 → 見た目 → ファイルへのアクセス(通知) → NAS と復元。
 * どの段階でも「飛ばす」で終われる。ファイルへのアクセスが許可されていなければ、そのあと従来の許可の画面が出る。
 * 設定の「このアプリについて」から、もう一度見られる。
 */
@Composable
internal fun OnboardingScreen(vm: FmViewModel, hasAccess: Boolean, onRequestAccess: () -> Unit, actions: Actions) {
    val pal = LocalPal.current
    val step = vm.onboardingStep
    Surface(Modifier.fillMaxSize(), color = pal.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.finishOnboarding() }) { Text(Txt(R.string.onboarding_skip)) }
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (step) {
                    0 -> WelcomeStep()
                    1 -> LookStep(vm)
                    2 -> AccessStep(hasAccess, onRequestAccess, actions)
                    else -> NasStep(vm)
                }
            }
            Dots(step)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) TextButton(onClick = { vm.onboardingBack() }) { Text(Txt(R.string.onboarding_back)) } else Spacer(Modifier.size(1.dp))
                if (step < Onboarding.STEPS - 1) {
                    Button(onClick = { vm.onboardingNext() }, enabled = Onboarding.canNext(step, hasAccess)) { Text(Txt(R.string.onboarding_next)) }
                } else {
                    Button(onClick = { vm.finishOnboarding() }) { Text(Txt(R.string.onboarding_done)) }
                }
            }
        }
    }
}

@Composable
private fun Dots(step: Int) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
        repeat(Onboarding.STEPS) { i ->
            Box(Modifier.padding(horizontal = 4.dp).size(8.dp).clip(CircleShape).background(if (i == step) pal.accent else pal.divider))
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LocalPal.current.text, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
}

@Composable
private fun Body(text: String) {
    Text(text, fontSize = 14.sp, color = LocalPal.current.subText, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
}

@Composable
private fun WelcomeStep() {
    val ctx = LocalContext.current
    Spacer(Modifier.height(24.dp))
    FolderGlyph(kindColor(FileKind.FOLDER), Modifier.size(80.dp))
    Spacer(Modifier.height(16.dp))
    Title(Txt(R.string.onboarding_welcome_title))
    Body(Txt(R.string.onboarding_welcome_text))
    Spacer(Modifier.height(24.dp))
    // 言語は先に決める(以降の画面の言葉が変わる)。切り替えると画面が作り直されるが、段階は VM が覚えている
    var lang by remember { mutableStateOf(Languages.saved(ctx)) }
    val langs = Languages.supported
    SegmentRow(
        Txt(R.string.settings_language),
        listOf(Txt(R.string.settings_language_system)) + langs.map { it.name },
        if (lang.isEmpty()) 0 else langs.indexOfFirst { it.tag == lang } + 1,
    ) { i ->
        val tag = if (i == 0) "" else langs[i - 1].tag
        if (tag != lang) {
            lang = tag
            Languages.change(ctx, tag)
            ctx.findActivity()?.recreate()
        }
    }
}

@Composable
private fun LookStep(vm: FmViewModel) {
    Spacer(Modifier.height(16.dp))
    Title(Txt(R.string.onboarding_look_title))
    Body(Txt(R.string.onboarding_look_text))
    Spacer(Modifier.height(16.dp))
    val themes = listOf("light", "dark", "system")
    SegmentRow(
        Txt(R.string.settings_ui_017),
        listOf(Txt(R.string.settings_ui_018), Txt(R.string.settings_ui_019), Txt(R.string.settings_ui_020)),
        themes.indexOf(vm.themeMode).coerceAtLeast(0),
    ) { vm.changeTheme(themes[it]) }
    ThemeColorRow(vm.themeColor) { vm.changeThemeColor(it) }
}

@Composable
private fun AccessStep(hasAccess: Boolean, onRequestAccess: () -> Unit, actions: Actions) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    // 許可の画面から戻ってきたときに、通知の許可の状態を取り直す
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val notifyNeeded = Build.VERSION.SDK_INT >= 33
    val notifyGranted = tick >= 0 && (!notifyNeeded || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    Spacer(Modifier.height(16.dp))
    Title(Txt(R.string.onboarding_access_title))
    Body(Txt(R.string.onboarding_access_text))
    Spacer(Modifier.height(12.dp))
    if (hasAccess) {
        Text(Txt(R.string.onboarding_access_granted), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = pal.accent)
    } else {
        Button(onClick = onRequestAccess) { Text(Txt(R.string.onboarding_access_open)) }
        Spacer(Modifier.height(6.dp))
        Text(Txt(R.string.onboarding_access_needed), fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
    }
    if (notifyNeeded) {
        Spacer(Modifier.height(28.dp))
        Title(Txt(R.string.onboarding_notify_title))
        Body(Txt(R.string.onboarding_notify_text))
        Spacer(Modifier.height(8.dp))
        if (notifyGranted) {
            Text(Txt(R.string.onboarding_notify_granted), fontSize = 14.sp, color = pal.accent)
        } else {
            OutlinedButton(onClick = { actions.requestNotifications() }) { Text(Txt(R.string.onboarding_notify_allow)) }
        }
    }
}

@Composable
private fun NasStep(vm: FmViewModel) {
    Spacer(Modifier.height(16.dp))
    Title(Txt(R.string.onboarding_nas_title))
    Body(Txt(R.string.onboarding_nas_text))
    Spacer(Modifier.height(20.dp))
    Button(onClick = { vm.finishOnboarding(); vm.openNetwork() }, modifier = Modifier.fillMaxWidth()) { Text(Txt(R.string.onboarding_nas_add)) }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { vm.finishOnboarding(); vm.startBackupImport() }, modifier = Modifier.fillMaxWidth()) { Text(Txt(R.string.onboarding_nas_restore)) }
}