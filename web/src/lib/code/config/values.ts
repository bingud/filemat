

class AppConfig {
    desktopWidth = 1024
    fileContentUrlPathPrefix = `/api/v1/file/content`

    preview = previewSizeConfig
}


export const previewSizes = [1, 2, 3, 4, 5]
export type previewSize = (typeof previewSizes)[number]

export const previewSizeLabels: Record<previewSize, string> = {
    1: "Tiny",
    2: "Small",
    3: "Medium",
    4: "Large",
    5: "Gigantic",
}

export type GridPreviewSize = { width: number, iconPadding: number, height: number, pixelSize: number }
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
            height: 5,
            iconPadding: 0.8,
            menuIconPadding: 0.5,
            pixelSize: 256
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
            pixelSize: 256
        },
        4: {
            width: 15,
            height: 13,
            iconPadding: 1.5,
            pixelSize: 426
        },
        5: {
            width: 25,
            height: 20,
            iconPadding: 2.5,
            pixelSize: 1080
        }
    }
}

export const config = new AppConfig()