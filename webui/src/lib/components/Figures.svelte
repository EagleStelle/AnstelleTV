<script lang="ts">
	import type { Snippet } from 'svelte';

	/** Figures side by side, wrapping when the column runs out; [wide] spaces the server page's counts. */
	let { children, wide = false }: { children: Snippet; wide?: boolean } = $props();
</script>

<div class={['flex flex-wrap items-start gap-y-3', wide ? 'gap-x-6 min-[840px]:gap-x-9' : 'gap-x-5 min-[840px]:gap-x-7']}>{@render children()}</div>
