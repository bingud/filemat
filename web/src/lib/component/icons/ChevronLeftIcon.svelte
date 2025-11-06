<script lang="ts">
    import SvgUse from "./util/SvgUse.svelte";

    let { class: classes }: { class?: string } = $props()
</script>
<SvgUse id="chevron-left" classes={classes}></SvgUse>