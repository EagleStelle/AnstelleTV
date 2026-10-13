<script lang="ts">
	import type { Component } from 'svelte';
	import Edit from '~icons/material-symbols/edit-outline';
	import Button from './Button.svelte';
	import Dialog from './Dialog.svelte';
	import Notice from './Notice.svelte';
	import Row from './Row.svelte';
	import TextField from './TextField.svelte';

	/**
	 * A value to type: a pencil at the row's end opens it in a field with Save and Cancel. [onsave]
	 * answers why the value can't be saved, or nothing when it was.
	 */
	interface Props {
		title: string;
		value: string;
		onsave: (value: string) => string | null | undefined | Promise<string | null | undefined>;
		summary?: string;
		icon?: Component<any>;
		label?: string;
		about?: string;
		maxlength?: number;
	}

	let { title, value, onsave, summary, icon, label = title, about, maxlength = 64 }: Props = $props();
	let open = $state(false);
	let draft = $state('');
	let error = $state<string | null>(null);
	let busy = $state(false);

	function edit() {
		draft = value;
		error = null;
		open = true;
	}

	async function save(event: SubmitEvent) {
		event.preventDefault();
		busy = true;
		try {
			error = (await onsave(draft.trim())) ?? null;
			if (!error) open = false;
		} finally {
			busy = false;
		}
	}
</script>

<Row {title} summary={summary ?? value} {icon} onclick={edit} aria-haspopup="dialog">
	{#snippet end()}<Edit class="size-6" />{/snippet}
</Row>

<Dialog bind:open {title} {about}>
	<form class="flex flex-col gap-2" onsubmit={save}>
		<!-- svelte-ignore a11y_autofocus -->
		<TextField {label} bind:value={draft} {maxlength} autofocus aria-invalid={!!error} />
		{#if error}<div class="-mx-4"><Notice text={error} error /></div>{/if}
		<div class="mt-3 flex flex-wrap gap-2 *:min-w-fit *:flex-1">
			<Button label="Cancel" onclick={() => (open = false)} />
			<Button label="Save" type="submit" primary disabled={busy} />
		</div>
	</form>
</Dialog>
