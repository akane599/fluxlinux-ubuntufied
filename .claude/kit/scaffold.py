#!/usr/bin/env python3
"""
Scaffold a minimal, buildable Kotlin + Jetpack Compose app into an (almost) empty folder.
Called by /bootstrap in "new project" mode. Mechanical parts only; features are Claude's job.

  python3 .claude/kit/scaffold.py --name "Task Master" --package com.you.taskmaster [--min-sdk 26]
        [--dir .] [--offline] [--dry-run]

What you get: a pinned version catalog (live resolution is opt-in with --latest), Gradle wrapper, single :app module, Material 3 theme split into token files
(Color/Type/Shape/Spacing/Theme — see the compose-design skill), one screen with a ViewModel and
immutable UiState, a unit test, and Compose Preview screenshot tests (light/dark/large font/tablet).
Preserves existing files; only .gitignore is merged. --offline never downloads a wrapper.
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tempfile, textwrap, urllib.error, urllib.request, zipfile
from xml.sax.saxutils import escape
from pathlib import Path

G = "https://dl.google.com/android/maven2"
C = "https://repo1.maven.org/maven2"
# Version snapshot inherited from kit v1. Verify on your SDK/JDK before treating it as a build baseline.
PINNED = {
    "gradle": "9.8.0", "agp": "9.4.1", "kotlin": "2.4.20", "composeBom": "2026.09.00",
    "activityCompose": "1.13.0", "lifecycle": "2.11.0", "coreKtx": "1.19.1",
    "collectionsImmutable": "0.5.2", "coroutines": "1.11.0", "junit": "4.13.2",
    "screenshot": "0.0.1-alpha16", "compileSdk": "37",
}
SOURCES = {  # key: (metadata url, allow prerelease)
    "agp": (f"{G}/com/android/tools/build/gradle/maven-metadata.xml", False),
    "kotlin": (f"{C}/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml", False),
    "composeBom": (f"{G}/androidx/compose/compose-bom/maven-metadata.xml", False),
    "activityCompose": (f"{G}/androidx/activity/activity-compose/maven-metadata.xml", False),
    "lifecycle": (f"{G}/androidx/lifecycle/lifecycle-runtime-ktx/maven-metadata.xml", False),
    "coreKtx": (f"{G}/androidx/core/core-ktx/maven-metadata.xml", False),
    "collectionsImmutable": (f"{C}/org/jetbrains/kotlinx/kotlinx-collections-immutable/maven-metadata.xml", False),
    "coroutines": (f"{C}/org/jetbrains/kotlinx/kotlinx-coroutines-test/maven-metadata.xml", False),
    "screenshot": (f"{G}/com/android/compose/screenshot/com.android.compose.screenshot.gradle.plugin/maven-metadata.xml", True),
}
PRE = re.compile(r"alpha|beta|rc|dev|eap|-M\d|snapshot", re.I)


def vkey(v):
    return [int(x) if x.isdigit() else x for x in re.split(r"[.\-]", v)]


def fetch(url, timeout=20):
    req = urllib.request.Request(url, headers={"User-Agent": "android-kit-scaffold/1.0 (+gradle-compatible)"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read().decode()
        except urllib.error.HTTPError as e:
            if e.code != 429 or attempt == 2:
                raise
            import time; time.sleep(5 * (attempt + 1))


def latest_compile_sdk():
    """Highest stable platform API level Google publishes (e.g. platforms;android-37.2 -> 37)."""
    xml = fetch("https://dl.google.com/android/repository/repository2-3.xml")
    levels = [int(m) for m in re.findall(r'path="platforms;android-(\d+)(?:\.\d+)?"', xml)]
    return str(max(levels))


def latest(url, allow_pre):
    vs = re.findall(r"<version>([^<]+)</version>", fetch(url))
    vs = [v for v in vs if allow_pre or not PRE.search(v)]
    return sorted(vs, key=lambda v: [(0, p) if isinstance(p, int) else (1, p) for p in vkey(v)])[-1]


def resolve(offline, use_latest=False):
    v = dict(PINNED)
    if offline or not use_latest:
        return v, ["using pinned version snapshot; a local build is still required"]
    notes = []
    for k, (url, pre) in SOURCES.items():
        try:
            v[k] = latest(url, pre)
        except Exception as e:
            notes.append(f"{k}: lookup failed ({e.__class__.__name__}), pinned {v[k]}")
    try:
        v["compileSdk"] = latest_compile_sdk()
    except Exception as e:
        notes.append(f"compileSdk: lookup failed, pinned {v['compileSdk']}")
    try:
        v["gradle"] = json.loads(fetch("https://services.gradle.org/versions/current"))["version"]
    except Exception as e:
        notes.append(f"gradle: lookup failed, pinned {v['gradle']}")
    return v, notes


def write(root, rel, content, created):
    p = root / rel
    if p.exists() and rel == ".gitignore":   # merge: android-kit may have created it already
        have = p.read_text().splitlines()
        add = [l for l in textwrap.dedent(content).strip().splitlines() if l not in have]
        if add:
            p.write_text(p.read_text().rstrip("\n") + "\n" + "\n".join(add) + "\n")
            created.append(rel)
        return
    if p.exists():
        return
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(textwrap.dedent(content).lstrip("\n"))
    created.append(rel)


def gradle_wrapper(root, version, created):
    wrapper_files = ["gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"]
    for rel in wrapper_files:
        dst = root / rel
        for part in (dst, *dst.parents):
            if part == root:
                break
            if part.is_symlink():
                raise ValueError(f"symlink in wrapper destination: {rel}")
            if part != dst and part.exists() and not part.is_dir():
                raise ValueError(f"non-directory wrapper ancestor: {part}")
        if dst.exists():
            raise ValueError("wrapper files already exist; refusing to overwrite a complete or partial wrapper")
    checksum = fetch(f"https://services.gradle.org/distributions/gradle-{version}-bin.zip.sha256").strip()
    if not re.fullmatch(r"[a-fA-F0-9]{64}", checksum):
        raise ValueError("invalid Gradle distribution checksum")
    with tempfile.TemporaryDirectory() as t:
        t = Path(t)
        (t / "settings.gradle.kts").write_text('rootProject.name = "wrapper"\n')
        gradle = shutil.which("gradle")
        if not gradle:
            cache = Path.home() / ".cache" / "android-kit" / f"gradle-{version}"
            if not (cache / "bin" / "gradle").exists():
                z = t / "g.zip"
                urllib.request.urlretrieve(f"https://services.gradle.org/distributions/gradle-{version}-bin.zip", z)
                if hashlib.sha256(z.read_bytes()).hexdigest() != checksum.lower():
                    raise ValueError("Gradle distribution checksum mismatch")
                with zipfile.ZipFile(z) as zf:
                    for info in zf.infolist():
                        if not (cache.parent / info.filename).resolve().is_relative_to(cache.resolve()):
                            raise ValueError("unsafe path in Gradle archive")
                    zf.extractall(cache.parent)
                (cache.parent / f"gradle-{version}").rename(cache) if not cache.exists() else None
                os.chmod(cache / "bin" / "gradle", 0o755)
            gradle = str(cache / "bin" / "gradle")
        subprocess.run([gradle, "-q", "wrapper", "--gradle-version", version, "--distribution-type", "bin", "--gradle-distribution-sha256-sum", checksum],
                       cwd=t, check=True, stdout=subprocess.DEVNULL, timeout=240)
        for rel in ["gradlew", "gradlew.bat", "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties"]:
            dst = root / rel
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(t / rel, dst)
            created.append(rel)
    os.chmod(root / "gradlew", 0o755)
    return f"gradle {version}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", required=True)
    ap.add_argument("--package", required=True)
    ap.add_argument("--min-sdk", type=int, default=26)
    ap.add_argument("--compile-sdk", type=int, default=0, help="default: API level in the version snapshot, or live metadata with --latest")
    ap.add_argument("--dir", default=".")
    ap.add_argument("--offline", action="store_true", help="no network; emit sources without the wrapper")
    ap.add_argument("--latest", action="store_true", help="opt into independently resolved versions; check compatibility")
    ap.add_argument("--no-wrapper", action="store_true", help="generate sources only")
    ap.add_argument("--wrapper-only", action="store_true", help="finish a prior offline scaffold using recorded Gradle version")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    if not re.fullmatch(r"[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+", a.package):
        sys.exit(f"invalid package: {a.package} (lowercase, dot-separated, e.g. com.you.app)")
    if not a.name.strip() or any(ord(c) < 32 for c in a.name):
        ap.error("name must be non-empty and contain no control characters")
    if any(part in {"class", "object", "fun", "when", "is", "in", "as", "package", "interface", "val", "var", "return", "for", "while", "do", "if", "else", "try", "throw", "true", "false", "null", "this", "super", "break", "continue", "import", "public", "private", "new", "int", "void", "switch", "case", "default", "static", "final", "enum", "extends", "implements"} for part in a.package.split('.')):
        ap.error("package contains a Kotlin/Java keyword")
    if a.offline and a.latest:
        ap.error("--offline and --latest cannot be combined")
    root = Path(a.dir).resolve()
    if a.wrapper_only:
        if a.offline or a.no_wrapper:
            ap.error("--wrapper-only requires network access")
        lock = json.loads((root / '.claude/kit/scaffold-versions.json').read_text())
        if a.dry_run:
            print(json.dumps({'wrapperVersion': lock['gradle']})); return
        print(gradle_wrapper(root, lock['gradle'], [])); return
    if (root / "settings.gradle.kts").exists() or (root / "settings.gradle").exists():
        sys.exit("a Gradle project already exists here; scaffold is for empty folders only")

    v, notes = resolve(a.offline, a.latest)
    # Snapshot by default; --latest may require a new SDK and toolchain compatibility review.
    csdk = a.compile_sdk or int(v["compileSdk"])
    if not 21 <= a.min_sdk <= csdk:
        ap.error("min-sdk must be between 21 and compile-sdk")
    if a.dry_run:
        print(json.dumps({"versions": v, "compileSdk": csdk, "notes": notes}, indent=1)); return

    pkg, path = a.package, a.package.replace(".", "/")
    app_name = escape(a.name.strip()).replace("\\", "\\\\").replace("'", "\\'").replace('"', '\\"')
    if app_name.startswith(("@", "?")):
        app_name = "\\" + app_name
    root_name = re.sub(r"[^A-Za-z0-9]", "", app_name) or "App"
    agp_major = int(v["agp"].split(".")[0])
    target_root = root
    staging = tempfile.TemporaryDirectory(prefix="android-kit-scaffold-")
    root = Path(staging.name)
    created = []
    W = lambda rel, c: write(root, rel, c, created)

    W("gradle/libs.versions.toml", f"""
        [versions]
        agp = "{v['agp']}"
        kotlin = "{v['kotlin']}"
        composeBom = "{v['composeBom']}"
        activityCompose = "{v['activityCompose']}"
        lifecycle = "{v['lifecycle']}"
        coreKtx = "{v['coreKtx']}"
        collectionsImmutable = "{v['collectionsImmutable']}"
        coroutines = "{v['coroutines']}"
        junit = "{v['junit']}"
        screenshot = "{v['screenshot']}"

        [libraries]
        androidx-core-ktx = {{ module = "androidx.core:core-ktx", version.ref = "coreKtx" }}
        androidx-activity-compose = {{ module = "androidx.activity:activity-compose", version.ref = "activityCompose" }}
        androidx-lifecycle-runtime-compose = {{ module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }}
        androidx-lifecycle-viewmodel-compose = {{ module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }}
        androidx-compose-bom = {{ module = "androidx.compose:compose-bom", version.ref = "composeBom" }}
        androidx-compose-ui = {{ module = "androidx.compose.ui:ui" }}
        androidx-compose-ui-graphics = {{ module = "androidx.compose.ui:ui-graphics" }}
        androidx-compose-ui-tooling = {{ module = "androidx.compose.ui:ui-tooling" }}
        androidx-compose-ui-tooling-preview = {{ module = "androidx.compose.ui:ui-tooling-preview" }}
        androidx-compose-material3 = {{ module = "androidx.compose.material3:material3" }}
        kotlinx-collections-immutable = {{ module = "org.jetbrains.kotlinx:kotlinx-collections-immutable", version.ref = "collectionsImmutable" }}
        kotlinx-coroutines-test = {{ module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }}
        junit = {{ module = "junit:junit", version.ref = "junit" }}
        screenshot-validation-api = {{ module = "com.android.tools.screenshot:screenshot-validation-api", version.ref = "screenshot" }}

        [plugins]
        android-application = {{ id = "com.android.application", version.ref = "agp" }}
        kotlin-android = {{ id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }}
        kotlin-compose = {{ id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }}
        compose-screenshot = {{ id = "com.android.compose.screenshot", version.ref = "screenshot" }}
        """)
    W("settings.gradle.kts", f"""
        pluginManagement {{
            repositories {{
                google()
                mavenCentral()
                gradlePluginPortal()
            }}
        }}
        dependencyResolutionManagement {{
            repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
            repositories {{
                google()
                mavenCentral()
            }}
        }}
        rootProject.name = "{root_name}"
        include(":app")
        """)
    root_plugins = ["alias(libs.plugins.android.application) apply false"]
    if agp_major < 9:
        root_plugins.append("alias(libs.plugins.kotlin.android) apply false")
    root_plugins += ["alias(libs.plugins.kotlin.compose) apply false", "alias(libs.plugins.compose.screenshot) apply false"]
    W("build.gradle.kts", "plugins {\n" + "".join(f"    {l}\n" for l in root_plugins) + "}\n")
    W("gradle.properties", """
        org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
        org.gradle.parallel=true
        org.gradle.caching=true
        org.gradle.configuration-cache=true
        android.useAndroidX=true
        android.nonTransitiveRClass=true
        android.experimental.enableScreenshotTest=true
        kotlin.code.style=official
        """)
    app_plugins = ["alias(libs.plugins.android.application)"]
    if agp_major < 9:
        app_plugins.append("alias(libs.plugins.kotlin.android)")
    app_plugins += ["alias(libs.plugins.kotlin.compose)", "alias(libs.plugins.compose.screenshot)"]
    app_plugins_block = "plugins {\n" + "".join(f"    {l}\n" for l in app_plugins) + "}\n\n"
    W("app/build.gradle.kts", app_plugins_block + textwrap.dedent(f"""
        android {{
            namespace = "{pkg}"
            compileSdk = {csdk}

            defaultConfig {{
                applicationId = "{pkg}"
                minSdk = {a.min_sdk}
                targetSdk = {csdk}
                versionCode = 1
                versionName = "0.1.0"
            }}

            buildTypes {{
                release {{
                    isMinifyEnabled = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                }}
            }}
            compileOptions {{
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }}
            buildFeatures {{
                compose = true
            }}
            experimentalProperties["android.experimental.enableScreenshotTest"] = true
        }}

        dependencies {{
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.lifecycle.runtime.compose)
            implementation(libs.androidx.lifecycle.viewmodel.compose)
            implementation(platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui)
            implementation(libs.androidx.compose.ui.graphics)
            implementation(libs.androidx.compose.ui.tooling.preview)
            implementation(libs.androidx.compose.material3)
            implementation(libs.kotlinx.collections.immutable)
            debugImplementation(libs.androidx.compose.ui.tooling)

            testImplementation(libs.junit)
            testImplementation(libs.kotlinx.coroutines.test)

            screenshotTestImplementation(libs.screenshot.validation.api)
            screenshotTestImplementation(libs.androidx.compose.ui.tooling)
        }}
        """).lstrip("\n"))
    W("app/proguard-rules.pro", "# Project-specific R8 rules. Add keep rules for reflection/serialization here.\n")
    W("app/src/main/AndroidManifest.xml", f"""
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application
                android:allowBackup="true"
                android:label="@string/app_name"
                android:supportsRtl="true"
                android:theme="@style/Theme.{root_name}">
                <activity
                    android:name=".MainActivity"
                    android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>
            </application>
        </manifest>
        """)
    W("app/src/main/res/values/strings.xml", f"""
        <resources>
            <string name="app_name">{app_name}</string>
            <string name="home_title">{app_name}</string>
            <string name="home_empty">Nothing here yet.</string>
            <string name="home_add">Add item</string>
        </resources>
        """)
    W("app/src/main/res/values/themes.xml", f"""
        <resources>
            <!-- Window theme only; all UI styling lives in ui/theme/*.kt -->
            <style name="Theme.{root_name}" parent="android:Theme.Material.Light.NoActionBar" />
        </resources>
        """)
    K = f"app/src/main/kotlin/{path}"
    W(f"{K}/MainActivity.kt", f"""
        package {pkg}

        import android.os.Bundle
        import androidx.activity.ComponentActivity
        import androidx.activity.compose.setContent
        import androidx.activity.enableEdgeToEdge
        import {pkg}.ui.home.HomeRoute
        import {pkg}.ui.theme.AppTheme

        class MainActivity : ComponentActivity() {{
            override fun onCreate(savedInstanceState: Bundle?) {{
                super.onCreate(savedInstanceState)
                enableEdgeToEdge()
                setContent {{
                    AppTheme {{
                        HomeRoute()
                    }}
                }}
            }}
        }}
        """)
    W(f"{K}/ui/theme/Color.kt", f"""
        package {pkg}.ui.theme

        import androidx.compose.material3.darkColorScheme
        import androidx.compose.material3.lightColorScheme
        import androidx.compose.ui.graphics.Color

        // PLACEHOLDER brand palette — neutral on purpose. Replace via /ui-overhaul (compose-design skill).
        // Screens never use these directly: they read MaterialTheme.colorScheme.*.
        internal object Brand {{
            val Ink = Color(0xFF1D2327)
            val Paper = Color(0xFFF7F7F5)
            val Primary = Color(0xFF2F5D62)
            val PrimarySoft = Color(0xFFCFE5E6)
            val PrimaryDark = Color(0xFF8CC7C9)
            val Accent = Color(0xFFB5654A)
            val AccentSoft = Color(0xFFF6DDD3)
            val Error = Color(0xFFB3261E)
        }}

        // Every role is set explicitly: anything left unset falls back to Material's baseline purple.
        internal val LightColors = lightColorScheme(
            primary = Brand.Primary, onPrimary = Color.White,
            primaryContainer = Brand.PrimarySoft, onPrimaryContainer = Color(0xFF0E2E31),
            secondary = Brand.Accent, onSecondary = Color.White,
            secondaryContainer = Brand.AccentSoft, onSecondaryContainer = Color(0xFF3E1A0E),
            tertiary = Color(0xFF55606B), onTertiary = Color.White,
            tertiaryContainer = Color(0xFFDCE3EA), onTertiaryContainer = Color(0xFF1A232B),
            background = Brand.Paper, onBackground = Brand.Ink,
            surface = Brand.Paper, onSurface = Brand.Ink,
            surfaceVariant = Color(0xFFE3E6E6), onSurfaceVariant = Color(0xFF444A4C),
            surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F2F0),
            surfaceContainer = Color(0xFFEBECEA), surfaceContainerHigh = Color(0xFFE5E6E4),
            surfaceContainerHighest = Color(0xFFDFE0DE),
            outline = Color(0xFF747A7C), outlineVariant = Color(0xFFC4C8C9),
            error = Brand.Error, onError = Color.White,
        )

        internal val DarkColors = darkColorScheme(
            primary = Brand.PrimaryDark, onPrimary = Color(0xFF00363A),
            primaryContainer = Color(0xFF1F4B4F), onPrimaryContainer = Brand.PrimarySoft,
            secondary = Color(0xFFF0B59E), onSecondary = Color(0xFF4E2111),
            secondaryContainer = Color(0xFF6B3825), onSecondaryContainer = Brand.AccentSoft,
            tertiary = Color(0xFFB9C4CF), onTertiary = Color(0xFF232E37),
            tertiaryContainer = Color(0xFF3A4550), onTertiaryContainer = Color(0xFFDCE3EA),
            background = Color(0xFF14181B), onBackground = Color(0xFFE4E6E7),
            surface = Color(0xFF14181B), onSurface = Color(0xFFE4E6E7),
            surfaceVariant = Color(0xFF3F4648), onSurfaceVariant = Color(0xFFC0C7C9),
            surfaceContainerLowest = Color(0xFF0F1214), surfaceContainerLow = Color(0xFF1B1F22),
            surfaceContainer = Color(0xFF1F2427), surfaceContainerHigh = Color(0xFF2A2F32),
            surfaceContainerHighest = Color(0xFF34393C),
            outline = Color(0xFF8A9193), outlineVariant = Color(0xFF3F4648),
            error = Color(0xFFF2B8B5), onError = Color(0xFF601410),
        )
        """)
    W(f"{K}/ui/theme/Type.kt", f"""
        package {pkg}.ui.theme

        import androidx.compose.material3.Typography

        // PLACEHOLDER: Material defaults. /ui-overhaul picks a family (res/font) and a full scale.
        internal val AppTypography = Typography()
        """)
    W(f"{K}/ui/theme/Shape.kt", f"""
        package {pkg}.ui.theme

        import androidx.compose.foundation.shape.RoundedCornerShape
        import androidx.compose.material3.Shapes
        import androidx.compose.ui.unit.dp

        // Radius encodes hierarchy: controls small, containers larger.
        internal val AppShapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        )
        """)
    W(f"{K}/ui/theme/Spacing.kt", f"""
        package {pkg}.ui.theme

        import androidx.compose.runtime.Immutable
        import androidx.compose.runtime.staticCompositionLocalOf
        import androidx.compose.ui.unit.Dp
        import androidx.compose.ui.unit.dp

        @Immutable
        data class Spacing(
            val xs: Dp = 4.dp,
            val sm: Dp = 8.dp,
            val md: Dp = 16.dp,
            val lg: Dp = 24.dp,
            val xl: Dp = 32.dp,
            val xxl: Dp = 48.dp,
        )

        val LocalSpacing = staticCompositionLocalOf {{ Spacing() }}
        """)
    W(f"{K}/ui/theme/Theme.kt", f"""
        package {pkg}.ui.theme

        import android.os.Build
        import androidx.compose.foundation.isSystemInDarkTheme
        import androidx.compose.material3.MaterialTheme
        import androidx.compose.material3.dynamicDarkColorScheme
        import androidx.compose.material3.dynamicLightColorScheme
        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.CompositionLocalProvider
        import androidx.compose.runtime.ReadOnlyComposable
        import androidx.compose.ui.platform.LocalContext

        /**
         * dynamicColor: decide per product. Branded apps usually keep it off; personal/utility apps may turn it on.
         */
        @Composable
        fun AppTheme(
            darkTheme: Boolean = isSystemInDarkTheme(),
            dynamicColor: Boolean = false,
            content: @Composable () -> Unit,
        ) {{
            val colors = when {{
                dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {{
                    val ctx = LocalContext.current
                    if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
                }}
                darkTheme -> DarkColors
                else -> LightColors
            }}
            CompositionLocalProvider(LocalSpacing provides Spacing()) {{
                MaterialTheme(
                    colorScheme = colors,
                    typography = AppTypography,
                    shapes = AppShapes,
                    content = content,
                )
            }}
        }}

        /** Access spacing tokens as MaterialTheme.spacing.md */
        val MaterialTheme.spacing: Spacing
            @Composable @ReadOnlyComposable
            get() = LocalSpacing.current
        """)
    W(f"{K}/ui/home/HomeViewModel.kt", f"""
        package {pkg}.ui.home

        import androidx.compose.runtime.Immutable
        import androidx.lifecycle.ViewModel
        import kotlinx.collections.immutable.ImmutableList
        import kotlinx.collections.immutable.persistentListOf
        import kotlinx.collections.immutable.toImmutableList
        import kotlinx.coroutines.flow.MutableStateFlow
        import kotlinx.coroutines.flow.StateFlow
        import kotlinx.coroutines.flow.asStateFlow
        import kotlinx.coroutines.flow.update

        @Immutable
        data class HomeUiState(
            val items: ImmutableList<String> = persistentListOf(),
        )

        class HomeViewModel : ViewModel() {{
            private val _state = MutableStateFlow(HomeUiState())
            val state: StateFlow<HomeUiState> = _state.asStateFlow()

            fun addItem() {{
                _state.update {{ s -> s.copy(items = (s.items + "Item ${{s.items.size + 1}}").toImmutableList()) }}
            }}
        }}
        """)
    W(f"{K}/ui/home/HomeScreen.kt", f"""
        package {pkg}.ui.home

        import androidx.compose.foundation.layout.Box
        import androidx.compose.foundation.layout.fillMaxSize
        import androidx.compose.foundation.layout.padding
        import androidx.compose.foundation.lazy.LazyColumn
        import androidx.compose.foundation.lazy.items
        import androidx.compose.material3.ExperimentalMaterial3Api
        import androidx.compose.material3.ExtendedFloatingActionButton
        import androidx.compose.material3.ListItem
        import androidx.compose.material3.MaterialTheme
        import androidx.compose.material3.Scaffold
        import androidx.compose.material3.Text
        import androidx.compose.material3.TopAppBar
        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.getValue
        import androidx.compose.ui.Alignment
        import androidx.compose.ui.Modifier
        import androidx.compose.ui.res.stringResource
        import androidx.lifecycle.compose.collectAsStateWithLifecycle
        import androidx.lifecycle.viewmodel.compose.viewModel
        import {pkg}.R
        import {pkg}.ui.theme.spacing

        @Composable
        fun HomeRoute(viewModel: HomeViewModel = viewModel()) {{
            val state by viewModel.state.collectAsStateWithLifecycle()
            HomeScreen(state = state, onAdd = viewModel::addItem)
        }}

        @OptIn(ExperimentalMaterial3Api::class)
        @Composable
        fun HomeScreen(
            state: HomeUiState,
            onAdd: () -> Unit,
            modifier: Modifier = Modifier,
        ) {{
            Scaffold(
                modifier = modifier,
                topBar = {{ TopAppBar(title = {{ Text(stringResource(R.string.home_title)) }}) }},
                floatingActionButton = {{
                    ExtendedFloatingActionButton(onClick = onAdd) {{ Text(stringResource(R.string.home_add)) }}
                }},
            ) {{ padding ->
                if (state.items.isEmpty()) {{
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {{
                        Text(
                            text = stringResource(R.string.home_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(MaterialTheme.spacing.lg),
                        )
                    }}
                }} else {{
                    LazyColumn(Modifier.fillMaxSize().padding(padding)) {{
                        items(state.items, key = {{ it }}) {{ item -> ListItem(headlineContent = {{ Text(item) }}) }}
                    }}
                }}
            }}
        }}
        """)
    W(f"app/src/test/kotlin/{path}/ui/home/HomeViewModelTest.kt", f"""
        package {pkg}.ui.home

        import org.junit.Assert.assertEquals
        import org.junit.Test

        class HomeViewModelTest {{
            @Test
            fun `addItem appends to immutable state`() {{
                val vm = HomeViewModel()
                vm.addItem()
                vm.addItem()
                assertEquals(listOf("Item 1", "Item 2"), vm.state.value.items)
            }}
        }}
        """)
    W(f"app/src/screenshotTest/kotlin/{path}/ui/home/HomeScreenScreenshots.kt", f"""
        package {pkg}.ui.home

        import android.content.res.Configuration
        import androidx.compose.runtime.Composable
        import androidx.compose.ui.tooling.preview.Preview
        import com.android.tools.screenshot.PreviewTest
        import {pkg}.ui.theme.AppTheme
        import kotlinx.collections.immutable.persistentListOf

        // Rendered on the JVM: ./gradlew :app:updateDebugScreenshotTest (write refs) / validateDebugScreenshotTest (diff)
        private val sample = HomeUiState(items = persistentListOf("Item 1", "Item 2", "Item 3"))

        @PreviewTest @Preview(name = "light", showBackground = true)
        @Composable fun HomeLight() = AppTheme(darkTheme = false) {{ HomeScreen(sample, onAdd = {{}}) }}

        @PreviewTest @Preview(name = "dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
        @Composable fun HomeDark() = AppTheme(darkTheme = true) {{ HomeScreen(sample, onAdd = {{}}) }}

        @PreviewTest @Preview(name = "large-font", showBackground = true, fontScale = 1.5f)
        @Composable fun HomeLargeFont() = AppTheme {{ HomeScreen(sample, onAdd = {{}}) }}

        @PreviewTest @Preview(name = "empty", showBackground = true)
        @Composable fun HomeEmpty() = AppTheme {{ HomeScreen(HomeUiState(), onAdd = {{}}) }}

        @PreviewTest @Preview(name = "tablet", showBackground = true, widthDp = 840, heightDp = 600)
        @Composable fun HomeTablet() = AppTheme {{ HomeScreen(sample, onAdd = {{}}) }}
        """)
    W(".gitignore", """
        *.iml
        .gradle/
        /local.properties
        /.idea/
        .DS_Store
        build/
        /captures
        .externalNativeBuild/
        .cxx/
        *.jks
        *.keystore
        """)
    wrapper = "not generated (offline/no-wrapper); rerun with --wrapper-only to finish"
    if not (a.offline or a.no_wrapper):
        wrapper = gradle_wrapper(root, v["gradle"], created)
    W('.claude/kit/scaffold-versions.json', json.dumps(v, indent=2) + '\n')
    try:
        for rel in created:
            dest = target_root / rel
            for parent in dest.parents:
                if parent.is_symlink():
                    raise ValueError(f"symlink in scaffold destination: {rel}")
                if parent.exists() and not parent.is_dir():
                    raise ValueError(f"non-directory scaffold ancestor: {parent}")
            if dest.is_symlink():
                raise ValueError(f"symlink in scaffold destination: {rel}")
            if dest.exists():
                if rel != '.gitignore':
                    raise ValueError(f"scaffold destination already exists: {rel}")
                if not dest.is_file():
                    raise ValueError('existing .gitignore must be a regular file')
        published, directories = [], []
        ignore = target_root / '.gitignore'
        previous_ignore = ignore.read_bytes() if ignore.exists() else None
        ignore_changed = False
        try:
            for rel in created:
                dest = target_root / rel
                if rel == '.gitignore' and previous_ignore is not None:
                    ignore_changed = True
                    write(target_root, rel, (root / rel).read_text(), [])
                else:
                    missing = []
                    parent = dest.parent
                    while not parent.exists():
                        missing.append(parent)
                        parent = parent.parent
                    for directory in reversed(missing):
                        directory.mkdir()
                        directories.append(directory)
                    # Track before copying: a failed copy can leave partial destination bytes.
                    published.append(dest)
                    shutil.copy2(root / rel, dest)
        except BaseException:
            for dest in reversed(published):
                dest.unlink(missing_ok=True)
            if ignore_changed:
                ignore.write_bytes(previous_ignore)
            for directory in reversed(directories):
                directory.rmdir()
            raise
    finally:
        staging.cleanup()
    print(json.dumps({"created": len(created), "compileSdk": csdk, "wrapper": wrapper,
                      "versions": v, "builtInKotlin": agp_major >= 9, "notes": notes}, indent=1))


if __name__ == "__main__":
    main()
