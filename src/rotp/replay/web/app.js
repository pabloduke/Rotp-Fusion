const $ = id => document.getElementById(id);
const pageSize = 30;
const replayId = new URLSearchParams(location.search).get('replay');
const base = '/api/replays/' + encodeURIComponent(replayId || '');
let replay, frame = 0, requested = 0, pageOffset = 0, playing = false;
let request, timer, imageUrl;
function el(tag, text, className) {
    const node = document.createElement(tag);
    if (text != null) node.textContent = text;
    if (className) node.className = className;
    return node;
}
function report(error) {
    $('error').textContent = error.message || String(error);
    $('error').hidden = false;
}
async function fetchOk(url, signal) {
    const response = await fetch(url, {signal});
    if (!response.ok) throw Error('Could not load ' + url + ' (' + response.status + ')');
    return response;
}
async function json(url, signal) { return (await fetchOk(url, signal)).json(); }

let matches = [];
const alphabet = new Intl.Collator(undefined, {sensitivity: 'accent'});
const dateFormat = new Intl.DateTimeFormat(undefined, {dateStyle: 'medium', timeStyle: 'short'});
const sortFields = {race: 'Races', model: 'Models', persona: 'Personas'};

function rosterValues(match, field) {
    const values = match.empires.map(empire => empire[field]).filter(Boolean).sort(alphabet.compare);
    return values.filter((value, i) => i === 0 || alphabet.compare(value, values[i - 1]) !== 0);
}

function renderLibrary() {
    const [field, order] = $('sort').value.split('-');
    const direction = order === 'desc' ? -1 : 1;
    const rows = matches.map(match => ({match, values: field === 'time' ? [] : rosterValues(match, field)}));
    rows.sort((a, b) => {
        let diff = 0;
        if (field === 'time') diff = a.match.timestampMillis - b.match.timestampMillis;
        else {
            if (!a.values.length && b.values.length) return 1;
            if (a.values.length && !b.values.length) return -1;
            const count = Math.min(a.values.length, b.values.length);
            for (let i = 0; i < count && !diff; i++) diff = alphabet.compare(a.values[i], b.values[i]);
            if (!diff) diff = a.values.length - b.values.length;
        }
        return direction * diff || alphabet.compare(a.match.name, b.match.name) || a.match.id.localeCompare(b.match.id);
    });
    const cards = rows.map(({match, values}) => {
        const card = el(match.error ? 'div' : 'a', null, 'match');
        if (!match.error) card.href = '?replay=' + encodeURIComponent(match.id);
        const heading = el('div', null, 'heading');
        const date = new Date(match.timestampMillis);
        const stamp = el('time', (match.fileModified ? 'File modified · ' : 'Completed · ') + dateFormat.format(date));
        stamp.dateTime = date.toISOString();
        stamp.title = date.toString();
        heading.append(el('strong', match.name), stamp);
        card.append(heading, el('small', match.error ||
            match.turns + ' turns · ' + match.empires.map(empire => empire.name).join(' · ')));
        if (field !== 'time') card.append(el('div', sortFields[field] + ' · ' + (values.join(', ') || 'Not recorded'), 'meta'));
        return card;
    });
    $('matches').replaceChildren(...(cards.length ? cards : [el('p','No completed replays yet.')]));
}

async function library() {
    $('refresh').disabled = true;
    $('error').hidden = true;
    try {
        matches = await json('/api/replays');
        renderLibrary();
    } catch (error) { report(error); }
    finally { $('refresh').disabled = false; }
}

function empireName(id) { return replay.empires.find(empire => empire.id === id)?.name || 'Empire #' + id; }
function eventCard(event) {
    const card = el('article', null, 'event');
    const jump = el('button', 'Turn ' + event.turn + ' · ' + event.kind);
    jump.onclick = () => seek(event.frame);
    const participants = [];
    if (event.empire >= 0) participants.push(empireName(event.empire));
    if (event.recipient != null && event.recipient >= 0) participants.push(empireName(event.recipient));
    card.append(jump, el('div', [participants.join(' → '),event.phase].filter(Boolean).join(' · '),'meta'));
    if (event.kind === 'Leader briefing' || event.kind === 'Leader detail') {
        const details = el('details');
        details.append(el('summary','View briefing'),el('p',event.details));
        card.append(details);
    } else card.append(el('p',event.details));
    return card;
}

function pause() {
    clearTimeout(timer);
    playing = false;
    $('play').textContent = 'Play';
}
function schedule() {
    clearTimeout(timer);
    if (frame >= replay.turns.length - 1) { pause(); return; }
    if (playing) timer = setTimeout(() => display(frame + 1), 1000 / Number($('speed').value));
}
function seek(index) { pause(); return display(index); }

// Commit the image, timeline, position and result together, including after rapid seeks.
async function display(index, offset) {
    request?.abort();
    const controller = new AbortController();
    request = controller;
    requested = Math.min(Math.max(index, 0), replay.turns.length - 1);
    const next = requested;
    const last = replay.turns.length - 1;
    const revealed = $('reveal').checked;
    const query = new URLSearchParams({maxFrame: revealed ? last : next, category:$('category').value, limit:pageSize});
    if ($('empire').value) query.set('empire',$('empire').value);
    if (offset != null) query.set('offset',offset);
    const signal = controller.signal;
    try {
        const [blob, history, outcome] = await Promise.all([
            fetchOk(base + '/map/' + next, signal).then(r => r.blob()),
            json(base + '/events?' + query, signal),
            revealed || next === last ? json(base + '/outcome',signal) : null
        ]);
        if (signal.aborted) return;
        const url = URL.createObjectURL(blob);
        const image = new Image();
        image.src = url;
        try { await image.decode(); }
        catch (error) { URL.revokeObjectURL(url); throw error; }
        if (signal.aborted) { URL.revokeObjectURL(url); return; }
        $('map').src = url;
        if (imageUrl) URL.revokeObjectURL(imageUrl);
        imageUrl = url;
        frame = next;
        const turn = replay.turns[frame];
        const first = replay.turns.indexOf(turn), end = replay.turns.lastIndexOf(turn);
        $('position').textContent = 'Turn ' + turn + (first === end ? '' : ' · Frame ' + (frame-first+1) + ' of ' + (end-first+1));
        $('scrub').value = frame;
        if (document.activeElement !== $('jump')) $('jump').value = turn;
        $('prev').disabled = frame === 0;
        $('next').disabled = frame === last;
        $('outcome').hidden = !outcome?.condition;
        $('outcome').textContent = outcome?.condition ? [outcome.condition,outcome.winners].filter(Boolean).join(' · ') : '';
        const cards = history.events.slice().reverse().map(eventCard);
        $('events').replaceChildren(...(cards.length ? cards : [el('p','No matching events through this frame.')]));
        $('events').scrollTop = 0;
        pageOffset = history.offset;
        $('older').disabled = pageOffset <= 0;
        $('newer').disabled = pageOffset + pageSize >= history.total;
        $('count').textContent = history.total ? (pageOffset+1) + '–' + (pageOffset+cards.length) + ' of ' + history.total : '0 events';
        $('error').hidden = true;
        schedule();
    } catch (error) {
        if (!signal.aborted) { pause(); report(error); }
    }
}

async function openReplay() {
    $('library').hidden = true;
    $('viewer').hidden = false;
    try {
        replay = await json(base);
        $('name').textContent = replay.name;
        document.title = replay.name + ' · ROTP Fusion';
        for (const empire of replay.empires) {
            $('empire').add(new Option(empire.name,empire.id));
            const identity = [empire.persona, empire.model || empire.builtinAi].filter(Boolean).join(' · ');
            const entry = el('span',empire.name + (identity ? ' — ' + identity : ''));
            entry.style.borderLeft = '4px solid ' + empire.color;
            entry.style.paddingLeft = '8px';
            $('roster').append(entry);
        }
        if (replay.downloads.length) $('downloads').append(el('small','Full-match files (contain spoilers)'),el('br'));
        for (const file of replay.downloads) {
            const link = el('a', {video:'Replay MP4',journal:'Strategy journal',diplomacy:'Diplomatic transcript'}[file]);
            link.href = base + '/files/' + file;
            $('downloads').append(link);
        }
        $('scrub').max = replay.turns.length - 1;
        $('jump').max = replay.turns[replay.turns.length - 1];
        $('scrub').oninput = () => seek(Number($('scrub').value));
        $('jump').onfocus = pause;
        $('jump').onkeydown = event => {
            if (event.key === 'Enter') event.currentTarget.blur();
        };
        $('jump').onchange = () => {
            const index = replay.turns.findIndex(t => t >= Number($('jump').value));
            seek(index < 0 ? replay.turns.length - 1 : index);
        };
        $('prev').onclick = () => seek(requested - 1);
        $('next').onclick = () => seek(requested + 1);
        $('play').onclick = () => {
            if (playing) { pause(); return; }
            playing = true;
            $('play').textContent = 'Pause';
            if (frame === replay.turns.length - 1) display(0);
            else schedule();
        };
        $('speed').onchange = schedule;
        for (const id of ['empire','category','reveal']) $(id).onchange = () => seek(requested);
        $('older').onclick = () => { pause(); display(frame,Math.max(0,pageOffset-pageSize)); };
        $('newer').onclick = () => { pause(); display(frame,pageOffset+pageSize); };
        await display(0);
    } catch (error) { report(error); }
}
$('refresh').onclick = library;
if (replayId) openReplay();
else {
    const url = new URL(location.href);
    const sort = $('sort');
    const saved = url.searchParams.get('sort');
    if (Array.from(sort.options).some(option => option.value === saved)) sort.value = saved;
    sort.onchange = () => {
        url.searchParams.set('sort', sort.value);
        history.replaceState(null, '', url);
        renderLibrary();
    };
    library();
}
