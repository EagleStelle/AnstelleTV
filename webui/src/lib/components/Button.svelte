<script lang="ts">
	import type { Component } from 'svelte';

	/**
	 * A verb, as a fill with no outline: the page's one primary is the accent (the text color when
	 * focused), the rest the raised surface (the accent when focused), a destructive one red
	 * (white with red ink when focused).
	 */
	interface Props {
		label: string;
		icon?: Component<any>;
		primary?: boolean;
		danger?: boolean;
		href?: string;
		download?: boolean;
		onclick?: () => void;
		disabled?: boolean;
		type?: 'button' | 'submit';
		/** The form a submit button sends when it sits outside it. */
		form?: string;
		autofocus?: boolean;
	}

	let { label, icon: Icon, primary = false, danger = false, href, download = false, onclick, disabled = false, type = 'button', form, autofocus = false }: Props = $props();

	const classes = $derived([
		'inline-flex min-h-10 items-center justify-center gap-2 rounded-control px-4 text-label-l font-semibold tracking-label whitespace-nowrap transition-colors',
		danger
			? 'bg-danger text-on-danger hover:bg-on-danger hover:text-danger focus-visible:bg-on-danger focus-visible:text-danger'
			: primary
				? 'bg-accent text-on-accent hover:bg-text hover:text-background focus-visible:bg-text focus-visible:text-background'
				: 'bg-surface-high text-text hover:bg-accent hover:text-on-accent focus-visible:bg-accent focus-visible:text-on-accent',
		'disabled:pointer-events-none disabled:opacity-60'
	]);
</script>

{#if href}
	<a class={classes} {href} download={download || undefined}>
		{#if Icon}<Icon class="size-4.5" />{/if}<span>{label}</span>
	</a>
{:else}
	<!-- svelte-ignore a11y_autofocus -->
	<button class={classes} {type} {form} {onclick} {disabled} {autofocus}>
		{#if Icon}<Icon class="size-4.5" />{/if}<span>{label}</span>
	</button>
{/if}
