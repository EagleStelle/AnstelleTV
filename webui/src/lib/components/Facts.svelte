<script lang="ts">
	import type { Snippet } from 'svelte';

	/** Facts side by side, wrapping when the column runs out; [wide] spaces the server page's connection. */
	let { children, wide = false }: { children: Snippet; wide?: boolean } = $props();
</script>

<div class={['flex min-w-0 flex-wrap gap-y-2.5', wide ? 'gap-x-5 min-[840px]:gap-x-8' : 'gap-x-5 min-[840px]:gap-x-7']}>{@render children()}</div>
