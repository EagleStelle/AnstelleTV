<script lang="ts">
	import type { Snippet } from 'svelte';

	/** Figures side by side, wrapping, their names on one line at the foot. */
	let { children }: { children: Snippet } = $props();
</script>

<div class="flex flex-wrap items-end gap-x-8 gap-y-3">{@render children()}</div>
