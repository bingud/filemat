<script lang="ts">

    let {
        id,
        classes,
    }: {
        id: string,
        classes?: string,
    } = $props()

</script>


<svg class="{classes || "h-full"} aspect-square">
    <use href="#{id}" />
</svg>