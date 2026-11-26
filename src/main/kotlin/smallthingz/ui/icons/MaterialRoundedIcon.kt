package smallthingz.ui.icons

import smallthingz.ui.components.CachedPathIconDrawable
import smallthingz.ui.components.NativePathIconMask

/**
 * Recommended common glyph set: AndroidX Material Rounded 1.7.8, 24 x 24 viewport.
 * Names match the pinned upstream pack. Paths are shared lazily; each drawable retains its own mask cache.
 * See docs/icons.md and src/main/resource-provenance/reverb-icons/SOURCE.json for provenance.
 */
enum class MaterialRoundedIcon(val autoMirrored: Boolean = false) {
    Settings,
    Warning,
    Share,
    ContentCopy,
    Delete,
    Edit,
    Info,
    Close,
    ContentCut,
    ArrowBack(true),
    Check,
    CheckCircle,
    RadioButtonUnchecked,
    SelectAll,
    PlayArrow,
    Pause,
    Save,
    FormatListBulleted(true),
    AudioFile,
    GraphicEq,
    Loop,
    RestartAlt,
    Folder,
    Undo(true),
    GppMaybe,
    ArrowDropDown,
    Add,
    Devices,
    LightMode,
    DarkMode,
    Visibility,
    VisibilityOff;

    val mask: NativePathIconMask by lazy { MaterialRoundedPaths.mask(this) }
}

/** Native cached renderer; callers retain the drawable and update tint/bounds on rebind. */
class MaterialRoundedIconDrawable(val icon: MaterialRoundedIcon, density: Float, tint: Int) :
    CachedPathIconDrawable({ icon.mask }, density, tint, icon.autoMirrored)
