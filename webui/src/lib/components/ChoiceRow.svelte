<script lang="ts" generics="T">
	import type { Component, Snippet } from 'svelte';
	import ArrowDropDown from '~icons/material-symbols/arrow-drop-down';
	import Button from './Button.svelte';
	import Dialog from './Dialog.svelte';
	import Row from './Row.svelte';

	/**
	 * A setting with a few values: the value in use sits under the title, a drop-down mark at the
	 * row's end, and the row opens the choices as a radio list, each with what it does.
	 */
	interface Props {
		title: string;
		value: T;
		choices: T[];
		label: (choice: T) => string;
		hint?: (choice: T) => string | undefined;
		onselect: (choice: T) => void;
		icon?: Component<any>;
		/** What the dialog says above the choices. */
		about?: string;
		/** The row's own line under its title; the value's label by default. */
		summary?: string;
		same?: (a: T, b: T) => boolean;
		/** Anything else the dialog holds, under the choices. */
		extra?: Snippet;
	}

	let { title, value, choices, label, hint, onselect, icon, about, summary, same = (a, b) => a === b, extra }: Props = $props();
	let open = $state(false);
	const name = `choice-${Math.random().toString(36).slice(2)}`;

	function pick(choice: T) {
		onselect(choice);
		if (!extra) open = false;
	}
</script>

<Row {title} summary={summary ?? label(value)} {icon} onclick={() => (open = true)} aria-haspopup="dialog">
	{#snippet end()}<ArrowDropDown class="size-6" />{/snippet}
</Row>

<Dialog bind:open {title} {about}>
	<div class="-mx-3 flex flex-col gap-0.5" role="radiogroup" aria-label={title}>
		{#each choices as choice, i (i)}
			{@const checked = same(choice, value)}
			<label
				class="group/choice flex min-h-14 cursor-pointer items-center gap-4 rounded-control px-3 py-2 transition-colors hover:bg-accent hover:text-on-accent has-focus-visible:bg-accent has-focus-visible:text-on-accent"
			>
				<input type="radio" {name} {checked} onchange={() => pick(choice)} class="peer sr-only" />
				<span
					class={[
						'grid size-5 shrink-0 place-items-center rounded-full border-2 group-hover/choice:border-on-accent group-has-focus-visible/choice:border-on-accent',
						checked ? 'border-accent' : 'border-text-muted'
					]}
					aria-hidden="true"
				>
					{#if checked}<span class="size-2.5 rounded-full bg-accent group-hover/choice:bg-on-accent group-has-focus-visible/choice:bg-on-accent"></span>{/if}
				</span>
				<span class="flex flex-col">
					<span>{label(choice)}</span>
					{#if hint?.(choice)}
						<span class="text-body-m text-text-muted group-hover/choice:text-on-accent/76 group-has-focus-visible/choice:text-on-accent/76">{hint(choice)}</span>
					{/if}
				</span>
			</label>
		{/each}
	</div>
	{@render extra?.()}
	{#snippet actions()}
		<Button label={extra ? 'Done' : 'Cancel'} onclick={() => (open = false)} />
	{/snippet}
</Dialog>
