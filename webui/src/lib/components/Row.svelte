<script lang="ts">
	import type { Component, Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';

	/**
	 * A settings row: flat on its ground with no fill at rest, a muted icon (or whatever [leading]
	 * draws), the title with its state under it, then what sits at its end says what it does.
	 * A link with [href], a button with [onclick], a fact with neither. Focus and hover fill it
	 * with the accent; children follow through the `row` group.
	 */
	interface Props extends Omit<HTMLAttributes<HTMLElement>, 'onclick' | 'title'> {
		title: string;
		summary?: string;
		icon?: Component<any>;
		leading?: Snippet;
		end?: Snippet;
		href?: string;
		onclick?: () => void;
		/** Destructive: the icon in red, and red when focused. */
		danger?: boolean;
		disabled?: boolean;
	}

	let { title, summary, icon: Icon, leading, end, href, onclick, danger = false, disabled = false, ...rest }: Props = $props();

	const live = $derived(!!(href || onclick) && !disabled);
	const classes = $derived([
		'group/row flex w-full items-center gap-4 rounded-control px-4 py-2.5 text-left text-text transition-colors',
		summary ? 'min-h-18' : 'min-h-14',
		live &&
			(danger
				? 'hover:bg-danger hover:text-on-danger focus-visible:bg-danger focus-visible:text-on-danger'
				: 'hover:bg-accent hover:text-on-accent focus-visible:bg-accent focus-visible:text-on-accent'),
		disabled && 'text-text-muted'
	]);
	/** Secondary content goes 76% of the text color on a filled row. */
	const quiet = $derived([
		live &&
			(danger
				? 'group-hover/row:text-on-danger group-focus-visible/row:text-on-danger'
				: 'group-hover/row:text-on-accent/76 group-focus-visible/row:text-on-accent/76')
	]);
</script>

{#snippet body()}
	{#if leading || Icon}
		<span class={['flex min-w-6 shrink-0 items-center justify-center', danger ? 'text-danger' : 'text-text-muted', quiet]}>
			{#if leading}{@render leading()}{:else if Icon}<Icon class="size-6" />{/if}
		</span>
	{/if}
	<span class="flex min-w-0 flex-1 flex-col">
		<span class="font-medium wrap-anywhere">{title}</span>
		{#if summary}<span class={['text-body-m wrap-anywhere text-text-muted', quiet]}>{summary}</span>{/if}
	</span>
	{#if end}<span class={['flex shrink-0 items-center gap-2 text-text-muted', quiet]}>{@render end()}</span>{/if}
{/snippet}

{#if href && !disabled}
	<a class={classes} {href} {...rest}>{@render body()}</a>
{:else if onclick}
	<button type="button" class={classes} {onclick} {disabled} {...rest}>{@render body()}</button>
{:else}
	<div class={classes} {...rest}>{@render body()}</div>
{/if}
