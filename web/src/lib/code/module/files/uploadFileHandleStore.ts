const DB_NAME = `filemat-upload-handles`
const STORE_NAME = `handles`
const DB_VERSION = 1

type FileSystemFileHandleLike = {
    getFile: () => Promise<File>
    queryPermission?: (descriptor?: { mode?: `read` | `readwrite` }) => Promise<PermissionState>
    requestPermission?: (descriptor?: { mode?: `read` | `readwrite` }) => Promise<PermissionState>
}

function openDb(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
        const request = indexedDB.open(DB_NAME, DB_VERSION)
        request.onerror = () => reject(request.error || new Error(`Failed to open handle database`))
        request.onsuccess = () => resolve(request.result)
        request.onupgradeneeded = () => {
            const db = request.result
            if (!db.objectStoreNames.contains(STORE_NAME)) {
                db.createObjectStore(STORE_NAME)
            }
        }
    })
}

async function withStore<T>(
    mode: IDBTransactionMode,
    run: (store: IDBObjectStore) => IDBRequest<T> | void,
): Promise<T | undefined> {
    const db = await openDb()
    return new Promise((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, mode)
        const store = tx.objectStore(STORE_NAME)
        let request: IDBRequest<T> | undefined
        try {
            const result = run(store)
            if (result) request = result
        } catch (error) {
            reject(error)
            return
        }
        tx.oncomplete = () => resolve(request?.result)
        tx.onerror = () => reject(tx.error || new Error(`Handle store transaction failed`))
        tx.onabort = () => reject(tx.error || new Error(`Handle store transaction aborted`))
    })
}

export async function putUploadFileHandle(key: string, handle: FileSystemFileHandleLike) {
    if (!key) return
    try {
        await withStore(`readwrite`, store => store.put(handle, key))
    } catch {
        // Private mode / unsupported structured clone — resume falls back to picker.
    }
}

export async function getUploadFileHandle(key: string | null | undefined): Promise<FileSystemFileHandleLike | null> {
    if (!key) return null
    try {
        const handle = await withStore<FileSystemFileHandleLike>(`readonly`, store => store.get(key))
        return handle || null
    } catch {
        return null
    }
}

export async function deleteUploadFileHandle(...keys: Array<string | null | undefined>) {
    const unique = [...new Set(keys.filter((key): key is string => !!key))]
    if (!unique.length) return
    try {
        await withStore(`readwrite`, store => {
            for (const key of unique) store.delete(key)
        })
    } catch {
        // ignore
    }
}

/**
 * Re-open a previously stored file handle. May prompt for permission (user gesture required).
 */
export async function fileFromStoredHandle(handle: FileSystemFileHandleLike): Promise<File | null> {
    try {
        // Prefer requestPermission first while we still have a user gesture from "Click to resume".
        if (typeof handle.requestPermission === `function`) {
            const permission = await handle.requestPermission({ mode: `read` })
            if (permission !== `granted`) return null
        } else if (typeof handle.queryPermission === `function`) {
            const permission = await handle.queryPermission({ mode: `read` })
            if (permission !== `granted`) return null
        }
        return await handle.getFile()
    } catch {
        return null
    }
}

export function supportsOpenFilePicker(): boolean {
    return typeof (window as any).showOpenFilePicker === `function`
}
