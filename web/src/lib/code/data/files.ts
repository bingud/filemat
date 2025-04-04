import { getFileExtension } from "../util/codeUtil.svelte";


const textFileCategoryList = ["html", "text", "md"]
const fileCategoryList = [...textFileCategoryList, "image", "video", "audio", "pdf"] as const;
export type FileCategory = (typeof fileCategoryList)[number]
export type TextFileCategory = (typeof textFileCategoryList)[number]

/**
 * Returns if a string is a valid file type category
 */
export function isFileCategory(value: any): value is FileCategory {
    return fileCategoryList.includes(value as FileCategory)
}

export function isTextFileCategory(value: any): value is TextFileCategory {
    return textFileCategoryList.includes(value)
}

export const fileCategories: Record<string, FileCategory> = {
    // HTML and Web Files
    "html": "html",
    "htm": "html",
    "xhtml": "html",
    "mhtml": "html",
    "php": "html",

    // Text and Code Files
    "txt": "text",
    "md": "md",
    "json": "text",
    "xml": "text",
    "js": "text",
    "css": "text",
    "csv": "text",
    "conf": "text",

    // Image Files
    "jpg": "image",
    "jpeg": "image",
    "png": "image",
    "gif": "image",
    "bmp": "image",
    "svg": "image",
    "webp": "image",
    "ico": "image",

    // Video Files
    "mp4": "video",
    "webm": "video",
    "ogg": "video",
    "ogv": "video",
    "mov": "video",
    "avi": "video",

    // Audio Files
    "mp3": "audio",
    "wav": "audio",
    "m4a": "audio",
    "aac": "audio",

    // PDF Files
    "pdf": "pdf",
}