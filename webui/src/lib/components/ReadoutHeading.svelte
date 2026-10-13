<script lang="ts">
	import type { Component } from 'svelte';

	/** A readout part's heading: the icon that names it and its name in the field voice, its setting at the end in bone, so the eye runs from what to how. */
	let { icon: Icon, name, value }: { icon: Component<any>; name: string; value?: string } = $props();
</script>

<p class="field-label flex min-w-0 items-center text-text-muted">
	<Icon class="mr-2.5 size-5 shrink-0" aria-hidden="true" />
	<span class="min-w-0 flex-1 truncate">{name}</span>
	{#if value}<span class="truncate pl-3 text-text">{value}</span>{/if}
</p>
