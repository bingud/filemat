import type { Role } from "../auth/types";
import { appState } from "../stateObjects/appState.svelte";
import { auth } from "../stateObjects/authState.svelte";
import { clientState } from "../stateObjects/clientState";
import type { ulid } from "../types/types";


export function getRole(id: ulid): Role | null {
    if (!appState.roleList) return null
    const role = appState.roleList.find((v) => v.roleId === id)
    return role ?? null
}

export function mapRoles(roleIds: ulid[]): Role[] | null {
    if (!appState.roleListObject) return null
    const list: (Role | null)[] = roleIds.map(v => appState.roleListObject![v])
    if (list.includes(null)) return null
    return list as Role[]
}

/**
 * Detects when the browser is idle
 */
export function onBrowserIdleChange(
    callbacks: { onIdle: () => void; onActive: () => void; idleTimeout?: number },
): () => void {
    const { onIdle, onActive, idleTimeout = 2000 } = callbacks;
    let idleCallbackId: number | null = null;

    if (
        typeof window === 'undefined' ||
        typeof document === 'undefined' ||
        typeof requestIdleCallback === 'undefined'
    ) {
        console.warn(
            'onBrowserIdleChange: Required browser APIs (window, document, requestIdleCallback) not available. Idle detection will not work.',
        );
        return () => {};
    }

    // Use clientState.isIdle as the source of truth
    const handleActivity = () => {
        if (clientState.isIdle) {
            clientState.isIdle = false;
            onActive();
        }

        if (idleCallbackId !== null) {
            cancelIdleCallback(idleCallbackId);
        }

        idleCallbackId = requestIdleCallback(
            () => {
                if (!clientState.isIdle) {
                    clientState.isIdle = true;
                    onIdle();
                }
                idleCallbackId = null;
            },
            { timeout: idleTimeout },
        );
    };

    const handleVisibilityChange = () => {
        if (document.visibilityState === 'visible') {
            handleActivity();
        }
    };

    const activityEvents = [
        'mousemove',
        'mousedown',
        'keydown',
        'touchstart',
        'scroll',
        'wheel',
    ];

    activityEvents.forEach((event) => {
        window.addEventListener(event, handleActivity, { passive: true });
    });
    document.addEventListener('visibilitychange', handleVisibilityChange);

    // Initial state: assume active
    clientState.isIdle = false;
    handleActivity();

    const stop = () => {
        if (idleCallbackId !== null) {
            cancelIdleCallback(idleCallbackId);
            idleCallbackId = null;
        }
        activityEvents.forEach((event) => {
            window.removeEventListener(event, handleActivity);
        });
        document.removeEventListener('visibilitychange', handleVisibilityChange);
    };

    return stop;
}