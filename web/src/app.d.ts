import 'svelte';

// See https://svelte.dev/docs/kit/types#app.d.ts
// for information about these interfaces
declare global {
	namespace App {
		// interface Error {}
		// interface Locals {}
		// interface PageData {}
		interface PageState {
            // phase?: number;
            popupPhase?: "sensitive-folders" | null;
        }
		// interface Platform {}
	}
}


export {};
