<script lang="ts">
	import type { Snippet } from 'svelte';

	/** A row with a key of its own at its end (a bin, a menu, a handle), right of the row for focus. */
	let { children, key }: { children: Snippet; key: Snippet } = $props();
</script>

<div class="flex items-center">
	<div class="min-w-0 flex-1">{@render children()}</div>
	{@render key()}
</div>
