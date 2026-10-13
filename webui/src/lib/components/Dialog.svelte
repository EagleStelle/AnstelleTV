<script lang="ts">
	import type { Snippet } from 'svelte';

	/**
	 * A sheet over its page, like the app's phone dialog: the surface with 4 px corners, a title,
	 * what it holds, then its buttons sharing one line. Escape or a click outside closes it.
	 */
	interface Props {
		open: boolean;
		title: string;
		about?: string;
		children?: Snippet;
		actions?: Snippet;
		onclose?: () => void;
		wide?: boolean;
	}

	let { open = $bindable(), title, about, children, actions, onclose, wide = false }: Props = $props();
	let dialog: HTMLDialogElement;

	$effect(() => {
		if (open && !dialog.open) dialog.showModal();
		if (!open && dialog.open) dialog.close();
	});

	function closed() {
		open = false;
		onclose?.();
	}

	function clickOutside(event: MouseEvent) {
		if (event.target === dialog) dialog.close();
	}
</script>

<!-- svelte-ignore a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
<dialog
	bind:this={dialog}
	onclose={closed}
	onclick={clickOutside}
	aria-labelledby="dialog-title"
	class={[
		'm-auto max-h-[min(100%-32px,720px)] rounded-panel border border-line bg-surface p-0 text-text backdrop:bg-background/80',
		wide ? 'w-[min(100%-32px,640px)]' : 'w-[min(100%-32px,480px)]'
	]}
>
	{#if open}
		<div class="flex flex-col gap-3 p-6">
			<h2 id="dialog-title" class="font-display text-headline-s font-semibold">{title}</h2>
			{#if about}<p class="text-body-m text-text-muted">{about}</p>{/if}
			{@render children?.()}
			{#if actions}<div class="mt-2 flex flex-wrap gap-2 *:min-w-fit *:flex-1">{@render actions()}</div>{/if}
		</div>
	{/if}
</dialog>
