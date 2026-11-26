package smallthingz.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Test
import smallthingz.ui.reverb.ReverbIcon

class MaterialRoundedIconTest {
    @Test fun compatibilityNamesRetainUpstreamMeaningAndRtlContract() {
        assertEquals(MaterialRoundedIcon.FormatListBulleted, ReverbIcon.Library.materialIcon)
        assertEquals(MaterialRoundedIcon.Warning, ReverbIcon.Incidents.materialIcon)
        assertEquals(MaterialRoundedIcon.ContentCut, ReverbIcon.Trim.materialIcon)
        assertEquals(MaterialRoundedIcon.GraphicEq, ReverbIcon.Capture.materialIcon)
        assertEquals(MaterialRoundedIcon.Visibility, ReverbIcon.Eye.materialIcon)
        assertEquals(MaterialRoundedIcon.VisibilityOff, ReverbIcon.EyeOff.materialIcon)
        assertEquals(
            setOf(MaterialRoundedIcon.ArrowBack, MaterialRoundedIcon.FormatListBulleted, MaterialRoundedIcon.Undo),
            MaterialRoundedIcon.entries.filter { it.autoMirrored }.toSet(),
        )
        assertEquals(
            setOf(ReverbIcon.Back, ReverbIcon.Library, ReverbIcon.Undo),
            ReverbIcon.entries.filter { it.autoMirrored }.toSet(),
        )
    }

    @Test fun domainArtworkIsNotMisrepresentedAsTheRecommendedPack() {
        assertEquals(
            setOf(ReverbIcon.OneShot, ReverbIcon.ExportRange, ReverbIcon.GitHub, ReverbIcon.Browser),
            ReverbIcon.entries.filter { it.materialIcon == null }.toSet(),
        )
        assertEquals(32, MaterialRoundedIcon.entries.size)
    }
}
