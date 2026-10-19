

class AppConfig {
    desktopWidth = 1024
    fileContentUrlPathPrefix = `/api/v1/file/content`

    preview = previewSizeConfig
}


export const previewSizes = [1, 2, 3, 4, 5, 6]
export type previewSize = (typeof previewSizes)[number]

export const previewSizeLabels: Record<previewSize, string> = {
    1: "Tiny",
    2: "Small",
    3: "Medium",
    4: "Large",
    5: "Gigantic",
    6: "Fullscreen",
}

export const fullscreenPreviewSize = 6

/**
 * Thumbnail sizes (longest edge in px) the fullscreen preview size picks from, matched to the screen.
 * Fixed steps keep the number of thumbnails cached per image small. 4096 is the server's maximum.
 */
export const fullscreenPixelSizes = [1024, 1536, 2048, 3072, 4096]

/** The smallest step that covers `neededPixels`, the largest one if none does */
export function pickFullscreenPixelSize(neededPixels: number): number {
    if (!(neededPixels > 0)) return fullscreenPixelSizes[2]
    return fullscreenPixelSizes.find(size => size >= neededPixels) ?? fullscreenPixelSizes[fullscreenPixelSizes.length - 1]
}

export type GridPreviewSize = { width: number, iconPadding: number, height: number, pixelSize: number, fullscreen?: boolean }
export type RowPreviewSize = { height: number, iconPadding: number, menuIconPadding: number, pixelSize: number }
const previewSizeConfig: {
    row: { [key: number]: RowPreviewSize },
    grid: { [key: number]: GridPreviewSize }
} = {
    row: {
        1: {
            height: 2,
            iconPadding: 0.1,
            menuIconPadding: 0.2,
            pixelSize: 48
        },
        2: {
            height: 2.5,
            iconPadding: 0.2,
            menuIconPadding: 0.4,
            pixelSize: 48
        },
        3: {
            height: 3.5,
            iconPadding: 0.5,
            menuIconPadding: 0.55,
            pixelSize: 48
        },
        4: {
            height: 5,
            iconPadding: 0.8,
            menuIconPadding: 0.5,
            pixelSize: 256
        },
        5: {
            height: 7,
            iconPadding: 1,
            menuIconPadding: 0.45,
            pixelSize: 420
        }
    },
    grid: {
        1: {
            width: 6,
            height: 5,
            iconPadding: 0,
            pixelSize: 256
        },
        2: {
            width: 8,
            height: 7,
            iconPadding: 0.2,
            pixelSize: 256
        },
        3: {
            width: 11,
            height: 10,
            iconPadding: 0.5,
            pixelSize: 420
        },
        4: {
            width: 15,
            height: 13,
            iconPadding: 1.5,
            pixelSize: 420
        },
        5: {
            width: 25,
            height: 20,
            iconPadding: 2.5,
            pixelSize: 1150
        },
        6: {
            width: 25,
            height: 20,
            iconPadding: 2.5,
            // Replaced by the screen based size, see `FileUiStateClasss.fullscreenPixelSize`
            pixelSize: 2048,
            fullscreen: true
        }
    }
}

export const config = new AppConfig()