<script lang="ts">
	import type { Component, Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';
	import ChevronRight from '~icons/material-symbols/chevron-right';
	import Row from './Row.svelte';

	/** A row that opens a page: a chevron at its end. */
	interface Props extends Omit<HTMLAttributes<HTMLElement>, 'title' | 'onclick'> {
		title: string;
		summary?: string;
		icon?: Component<any>;
		leading?: Snippet;
		href: string;
		value?: string;
	}

	let { title, summary, icon, leading, href, value, ...rest }: Props = $props();
</script>

<Row {title} {summary} {icon} {leading} {href} {...rest}>
	{#snippet end()}
		{#if value}<span class="text-label-l font-semibold tracking-label">{value}</span>{/if}
		<ChevronRight class="size-6" />
	{/snippet}
</Row>
