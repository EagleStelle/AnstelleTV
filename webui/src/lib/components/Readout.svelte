<script lang="ts">
	import type { Snippet } from 'svelte';

	/** Facts read like a set's system-information panel: a hairline frame with no fill, never something to press. */
	let { children }: { children: Snippet } = $props();
</script>

<div class="flex flex-col gap-4 rounded-panel border border-line p-4.5">{@render children()}</div>
