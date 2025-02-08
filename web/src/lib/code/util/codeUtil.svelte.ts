

export function makeIdempotent<T, Args extends any[]>(
    fn: (isRunning: boolean, ...args: Args) => T | Promise<T>
): (...args: Args) => Promise<T> {
    let runningCount = 0

    return (...args: Args): Promise<T> => {
        // Determine if another call is already in progress.
        const isAlreadyRunning = runningCount > 0
        runningCount++

        try {
            const result = fn(isAlreadyRunning, ...args)
            // Wrap the result in a promise to handle both sync and async cases.
            return Promise.resolve(result).finally(() => {
                runningCount--
            });
        } catch (error) {
            runningCount--
            return Promise.reject(error)
        }
    }
}
  

export function isBlank(str: string | null | undefined): boolean {
    return !str || str.trim().length === 0;
}