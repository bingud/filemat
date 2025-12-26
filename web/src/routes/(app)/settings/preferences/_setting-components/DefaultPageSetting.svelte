<script lang="ts">
    import { setPreferenceSetting } from "$lib/code/module/settings";
    import { appState, filePagePaths } from "$lib/code/stateObjects/appState.svelte";
    import { run } from "$lib/code/util/codeUtil.svelte";
    import Select from "$lib/component/Select.svelte";


    const paths = run(() => {
        let paths = { ...filePagePaths } as any
        Object.keys(paths).forEach((key) => {
            const text = paths[key]
            paths[key] = text.charAt(0).toUpperCase() + text.slice(1).replace(/([A-Z])/g, " $1").trim()
        })
        return paths
    })

    function onSelect(value: string) {
        setPreferenceSetting("default_page_path", value)
    }
</script>




<div class="flex flex-col gap-4">
    <div class="flex flex-col gap-2">
        <h3 class="font-medium">Default page</h3>

        <p class="text-sm text-neutral-600 dark:text-neutral-400">
            Set which files tab will be opened by default.
        </p>
    </div>

    <Select 
        bind:value={appState.settings.defaultPagePath}
        items={paths} 
        buttonClasses="min-w-fit max-w-full w-[15rem] capitalize"
        dropdownClasses="capitalize bg-surface-content py-2"
        itemClasses="hover:bg-surface-content-hover "
        bgColor="bg-bg"
        {onSelect}
    ></Select>
</div>