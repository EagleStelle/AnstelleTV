<script lang="ts">
	import type { Component } from 'svelte';

	/**
	 * One fact: the icon names it, so the text stands without a label; [detail] follows it quietly.
	 * [label] says what the icon names for a screen reader when the text alone wouldn't.
	 */
	let { icon: Icon, text, detail, quiet = false, label }: { icon: Component<any>; text: string; detail?: string; quiet?: boolean; label?: string } = $props();
</script>

<p class="flex max-w-full min-w-0 items-center gap-2 text-body-m">
	{#if label}<Icon class="size-4.5 shrink-0 text-text-muted" aria-label={label} role="img" />{:else}<Icon class="size-4.5 shrink-0 text-text-muted" aria-hidden="true" />{/if}
	<span class={['truncate', quiet ? 'text-text-muted' : 'text-text']}>{text}</span>
	{#if detail}<span class="truncate text-text-muted">{detail}</span>{/if}
</p>
