# `ui/theme/` — Design Tokens

## Files

| File | What it is |
|---|---|
| `Theme.kt` | `StreamifyTheme` — the app theme + `StreamifyColors`/`StreamifyType` object surfaces. |
| `Color.kt` | Raw palette (`Primary`, `TextMain`, `BgCard`, ...). |
| `Type.kt` | Typography scale. |
| `Shape.kt` | Corner radii. |
| `Dimens.kt` | `StreamifyDimens` spacing/sizing tokens. |
| `ScreenConfiguration.kt` | Screen-size classes (`centerInLargeScreen` helpers). |

Tokens are accessed as `StreamifyColors.X` / `StreamifyDimens.Y` —
screens wildcard-import `ui.theme.*` by convention.
