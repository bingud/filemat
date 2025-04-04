import { getFileExtension } from "../util/codeUtil.svelte";


const fileCategoryList = ["html", "text", "image", "video", "audio", "pdf", "md"] as const;
export type FileCategory = (typeof fileCategoryList)[number]

/**
 * Returns if a string is a valid file type category
 */
export function isFileCategory(value: any): value is FileCategory {
    return fileCategoryList.includes(value as FileCategory)
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