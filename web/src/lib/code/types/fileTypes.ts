
export const fileSortingModes = {
    "name": "Name",
    "modified": "Last modified",
    "size": "Size",
    "created": "Creation date",
} as const

export const fileSortingDirections = [ "asc", "desc" ] as const

export type FileSortingMode = keyof typeof fileSortingModes
export type SortingDirection = typeof fileSortingDirections[number]