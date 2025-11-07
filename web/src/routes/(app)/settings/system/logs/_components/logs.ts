

export type LogLevel = "DEBUG" | "INFO" | "WARN" | "ERROR" | "FATAL"
export type LogType = "SYSTEM" | "SECURITY" | "AUTH" | "AUDIT"

export type Log = {
    logId: number,
    level: LogLevel,
    type: LogType,
    action: string,
    createdDate: number,
    description: string,
    message: string,
    initiatorId: string | null,
    initiatorIp: string | null,
    targetId: string | null,
    metadata: {[key: string]: string} | null,
}