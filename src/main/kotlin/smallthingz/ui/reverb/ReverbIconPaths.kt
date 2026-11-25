// Reverb domain glyphs retain their existing provenance and licenses; see docs/icons.md.
package smallthingz.ui.reverb

import android.graphics.Path
import smallthingz.ui.components.NativePathIconMask
import smallthingz.ui.icons.MaterialRoundedIcon
import smallthingz.ui.icons.MaterialRoundedPaths

/** Compatibility names for Reverb semantics. Common callers should use MaterialRoundedIcon. */
enum class ReverbIcon(
    val materialIcon: MaterialRoundedIcon? = null,
    val autoMirrored: Boolean = materialIcon?.autoMirrored ?: false,
) {
    Settings(MaterialRoundedIcon.Settings),
    Incidents(MaterialRoundedIcon.Warning),
    Share(MaterialRoundedIcon.Share),
    Copy(MaterialRoundedIcon.ContentCopy),
    Delete(MaterialRoundedIcon.Delete),
    Edit(MaterialRoundedIcon.Edit),
    Info(MaterialRoundedIcon.Info),
    Close(MaterialRoundedIcon.Close),
    Trim(MaterialRoundedIcon.ContentCut),
    Back(MaterialRoundedIcon.ArrowBack),
    Check(MaterialRoundedIcon.Check),
    Checked(MaterialRoundedIcon.CheckCircle),
    Unchecked(MaterialRoundedIcon.RadioButtonUnchecked),
    MultiSelect(MaterialRoundedIcon.SelectAll),
    Play(MaterialRoundedIcon.PlayArrow),
    Pause(MaterialRoundedIcon.Pause),
    Save(MaterialRoundedIcon.Save),
    ExportRange,
    Library(MaterialRoundedIcon.FormatListBulleted),
    AudioFile(MaterialRoundedIcon.AudioFile),
    Capture(MaterialRoundedIcon.GraphicEq),
    OneShot,
    Looping(MaterialRoundedIcon.Loop),
    Reset(MaterialRoundedIcon.RestartAlt),
    Folder(MaterialRoundedIcon.Folder),
    Undo(MaterialRoundedIcon.Undo),
    BackgroundWarning(MaterialRoundedIcon.GppMaybe),
    ArrowDropDown(MaterialRoundedIcon.ArrowDropDown),
    Add(MaterialRoundedIcon.Add),
    GitHub,
    Browser;

    val mask: NativePathIconMask by lazy { ReverbIconPaths.mask(this) }
}

/** Reverb domain assets and compatibility access to the common immutable masks. */
object ReverbIconPaths {
    fun copyLibraryBackPath(): Path = MaterialRoundedPaths.copyArrowBackPath()
    fun copyHomeIncidentPath(): Path = MaterialRoundedPaths.copyWarningPath()

    fun mask(icon: ReverbIcon): NativePathIconMask = icon.materialIcon?.mask ?: when (icon) {
        ReverbIcon.Browser -> NativePathIconMask(browserPath)
        ReverbIcon.GitHub -> NativePathIconMask(githubPath)
        ReverbIcon.ExportRange -> NativePathIconMask(exportRangePath0, strokeWidth = 2.2f)
        ReverbIcon.OneShot -> NativePathIconMask(oneShotPath0, strokeWidth = 2.2f)
        else -> error("Missing common icon mapping: $icon")
    }

    // Google Material filled Language (Apache-2.0); direct Float32 PathParser-equivalent commands.
    // Historical source path: resource-provenance/reverb-icons/browser-path.txt.
    private val browserPath: Path by lazy {
        Path().apply {
            moveTo(11.9899998f, 2f)
            cubicTo(6.46999979f, 2f, 2f, 6.48000002f, 2f, 12f)
            rCubicTo(0f, 5.51999998f, 4.46999979f, 10f, 9.98999977f, 10f)
            cubicTo(17.5200005f, 22f, 22f, 17.5200005f, 22f, 12f)
            cubicTo(22f, 6.47999954f, 17.5200005f, 2f, 11.9899998f, 2f)
            close()
            moveTo(11.9899998f, 2f)
            rMoveTo(6.92999983f, 6f)
            rLineTo(-2.95000005f, 0f)
            rCubicTo(-0.319999993f, -1.25f, -0.779999971f, -2.45000005f, -1.38f, -3.55999994f)
            rCubicTo(1.84000003f, 0.629999995f, 3.36999989f, 1.90999997f, 4.32999992f, 3.55999994f)
            close()
            moveTo(18.9200001f, 8f)
            moveTo(12f, 4.03999996f)
            rCubicTo(0.829999983f, 1.20000005f, 1.48000002f, 2.52999997f, 1.90999997f, 3.96000004f)
            rLineTo(-3.81999993f, 0f)
            rCubicTo(0.430000007f, -1.42999995f, 1.08000004f, -2.75999999f, 1.90999997f, -3.96000004f)
            close()
            moveTo(12f, 4.03999996f)
            moveTo(4.26000023f, 14f)
            cubicTo(4.0999999f, 13.3599997f, 4f, 12.6899996f, 4f, 12f)
            rCubicTo(0f, -0.68999958f, 0.100000001f, -1.36000001f, 0.25999999f, -2f)
            rLineTo(3.38000011f, 0f)
            rCubicTo(-0.0799999982f, 0.660000026f, -0.140000001f, 1.32000005f, -0.140000001f, 2f)
            rCubicTo(0f, 0.680000007f, 0.0599999987f, 1.34000003f, 0.140000001f, 2f)
            lineTo(4.26000023f, 14f)
            close()
            moveTo(4.26000023f, 14f)
            rMoveTo(0.819999993f, 2f)
            rLineTo(2.95000005f, 0f)
            rCubicTo(0.319999993f, 1.25f, 0.779999971f, 2.45000005f, 1.38f, 3.55999994f)
            rCubicTo(-1.84000003f, -0.629999995f, -3.36999989f, -1.89999998f, -4.32999992f, -3.55999994f)
            close()
            moveTo(5.0800004f, 16f)
            rMoveTo(2.95000005f, -8f)
            lineTo(5.07999992f, 8f)
            rCubicTo(0.959999979f, -1.65999997f, 2.49000001f, -2.93000007f, 4.32999992f, -3.55999994f)
            cubicTo(8.81000042f, 5.55000019f, 8.35000038f, 6.75f, 8.02999973f, 8f)
            close()
            moveTo(8.03000069f, 8f)
            moveTo(12f, 19.9599991f)
            rCubicTo(-0.829999983f, -1.20000005f, -1.48000002f, -2.52999997f, -1.90999997f, -3.96000004f)
            rLineTo(3.81999993f, 0f)
            rCubicTo(-0.430000007f, 1.42999995f, -1.08000004f, 2.75999999f, -1.90999997f, 3.96000004f)
            close()
            moveTo(12f, 19.9599991f)
            moveTo(14.3400002f, 14f)
            lineTo(9.65999985f, 14f)
            rCubicTo(-0.0900000036f, -0.660000026f, -0.159999996f, -1.32000005f, -0.159999996f, -2f)
            rCubicTo(0f, -0.680000007f, 0.0700000003f, -1.35000002f, 0.159999996f, -2f)
            rLineTo(4.67999983f, 0f)
            rCubicTo(0.0900000036f, 0.649999976f, 0.159999996f, 1.32000005f, 0.159999996f, 2f)
            rCubicTo(0f, 0.680000007f, -0.0700000003f, 1.34000003f, -0.159999996f, 2f)
            close()
            moveTo(14.3400002f, 14f)
            rMoveTo(0.25f, 5.55999994f)
            rCubicTo(0.600000024f, -1.11000001f, 1.05999994f, -2.30999994f, 1.38f, -3.55999994f)
            rLineTo(2.95000005f, 0f)
            rCubicTo(-0.959999979f, 1.64999998f, -2.49000001f, 2.93000007f, -4.32999992f, 3.55999994f)
            close()
            moveTo(14.5900002f, 19.5599995f)
            moveTo(16.3600006f, 14f)
            rCubicTo(0.0799999982f, -0.660000026f, 0.140000001f, -1.32000005f, 0.140000001f, -2f)
            rCubicTo(0f, -0.680000007f, -0.0599999987f, -1.34000003f, -0.140000001f, -2f)
            rLineTo(3.38000011f, 0f)
            rCubicTo(0.159999996f, 0.639999986f, 0.25999999f, 1.30999994f, 0.25999999f, 2f)
            rCubicTo(0f, 0.690000534f, -0.100000001f, 1.36000001f, -0.25999999f, 2f)
            rLineTo(-3.38000011f, 0f)
            close()
            moveTo(16.3600006f, 14f)
        }
    }

    private val exportRangePath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(5.0f, 5.0f)
            lineTo(5.0f, 19.0f)
            moveTo(19.0f, 5.0f)
            lineTo(19.0f, 19.0f)
            moveTo(8.5f, 8.0f)
            lineTo(15.5f, 8.0f)
            moveTo(12.0f, 11.0f)
            lineTo(12.0f, 17.0f)
            moveTo(9.25f, 14.5f)
            lineTo(12.0f, 17.25f)
            lineTo(14.75f, 14.5f)
        }
    }

    private val oneShotPath0: Path by lazy {
        Path().apply {
            fillType = Path.FillType.WINDING
            moveTo(5.0f, 5.0f)
            lineTo(5.0f, 19.0f)
            moveTo(8.5f, 12.0f)
            lineTo(19.0f, 12.0f)
            moveTo(15.0f, 8.0f)
            lineTo(19.0f, 12.0f)
            lineTo(15.0f, 16.0f)
        }
    }

    private val githubPath: Path by lazy {
        Path().apply {
            moveTo(12f, 2f)
            cubicTo(6.48f, 2f, 2f, 6.58f, 2f, 12.22f)
            rCubicTo(0f, 4.5f, 2.87f, 8.31f, 6.84f, 9.65f)
            rCubicTo(0.5f, 0.1f, 0.68f, -0.22f, 0.68f, -0.49f)
            rCubicTo(0f, -0.24f, -0.01f, -1.04f, -0.01f, -1.89f)
            rCubicTo(-2.78f, 0.62f, -3.37f, -1.21f, -3.37f, -1.21f)
            rCubicTo(-0.45f, -1.18f, -1.11f, -1.49f, -1.11f, -1.49f)
            rCubicTo(-0.91f, -0.64f, 0.07f, -0.63f, 0.07f, -0.63f)
            rCubicTo(1f, 0.07f, 1.53f, 1.05f, 1.53f, 1.05f)
            rCubicTo(0.9f, 1.57f, 2.36f, 1.12f, 2.94f, 0.85f)
            rCubicTo(0.09f, -0.67f, 0.35f, -1.12f, 0.63f, -1.37f)
            rCubicTo(-2.22f, -0.26f, -4.55f, -1.14f, -4.55f, -5.06f)
            rCubicTo(0f, -1.12f, 0.39f, -2.03f, 1.03f, -2.75f)
            rCubicTo(-0.1f, -0.26f, -0.45f, -1.31f, 0.1f, -2.73f)
            rCubicTo(0f, 0f, 0.84f, -0.28f, 2.75f, 1.05f)
            rCubicTo(0.8f, -0.23f, 1.65f, -0.35f, 2.5f, -0.35f)
            rCubicTo(0.85f, 0f, 1.7f, 0.12f, 2.5f, 0.35f)
            rCubicTo(1.91f, -1.33f, 2.75f, -1.05f, 2.75f, -1.05f)
            rCubicTo(0.55f, 1.42f, 0.2f, 2.47f, 0.1f, 2.73f)
            rCubicTo(0.64f, 0.72f, 1.03f, 1.63f, 1.03f, 2.75f)
            rCubicTo(0f, 3.93f, -2.33f, 4.8f, -4.56f, 5.05f)
            rCubicTo(0.36f, 0.32f, 0.68f, 0.93f, 0.68f, 1.88f)
            rCubicTo(0f, 1.36f, -0.01f, 2.45f, -0.01f, 2.78f)
            rCubicTo(0f, 0.27f, 0.18f, 0.6f, 0.69f, 0.49f)
            cubicTo(19.13f, 20.52f, 22f, 16.72f, 22f, 12.22f)
            cubicTo(22f, 6.58f, 17.52f, 2f, 12f, 2f)
            close()
        }
    }
}
