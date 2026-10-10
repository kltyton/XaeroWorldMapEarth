var xaeroMapSkinState = Vue.shallowRef({ controls: [], visible: true });
var xaeroMapSkinApp = null;

function xaeroMapSkinText(text) {
    return text.indexOf('\u00a7') < 0 ? text : Vue.h(McUIVue.McFormattedText, { text: text });
}

function xaeroMapSkinLabel(control) {
    if (control.role === 'icon') return '';
    if (typeof control.label === 'string') return xaeroMapSkinText(control.label);
    return (control.runs || []).map(function (run) {
        return Vue.h('span', { style: run.color ? { color: run.color } : {} }, xaeroMapSkinText(run.text || ''));
    });
}

function xaeroMapSliderCaption(control) {
    var caption = control.label || '';
    var separator = Math.max(caption.lastIndexOf(':'), caption.lastIndexOf('：'));
    return separator < 0 ? { label: caption, value: '' } : {
        label: caption.slice(0, separator),
        value: caption.slice(separator + 1).trim()
    };
}

function xaeroMapControlStyle(control) {
    var unit = control.height / 20;
    var style = {
        position: 'absolute', left: control.x + 'px', top: control.y + 'px',
        width: control.width + 'px', height: control.height + 'px',
        display: control.visible ? '' : 'none',
        '--xaero-height': control.height + 'px',
        '--xaero-font-size': (9 * unit) + 'px',
        '--xaero-label-size': (8 * unit) + 'px',
        '--xaero-label-height': (8 * unit) + 'px',
        '--xaero-inset': (4 * unit) + 'px',
        '--xaero-pixel': unit + 'px',
        '--xaero-depth': (2 * unit) + 'px'
    };
    if (control.role === 'slider') {
        var scale = unit / 2;
        style['--xaero-slider-scale'] = scale;
        style['--xaero-slider-width'] = (control.width - 8 * unit) / scale + 'px';
    }
    return style;
}

function xaeroMapStyleText(style) {
    return Object.keys(style).map(function (key) {
        if (style[key] === '') return '';
        var property = key.replace(/[A-Z]/g, function (letter) { return '-' + letter.toLowerCase(); });
        return property + ':' + style[key] + ';';
    }).join('');
}

function xaeroMapControl(control, settings) {
    var style = xaeroMapControlStyle(control);
    if (control.role === 'slider') {
        var caption = xaeroMapSliderCaption(control);
        return Vue.h('div', {
            key: control.key, class: 'xaeroearth-proxy is-slider',
            'data-native-key': control.key, style: xaeroMapStyleText(style)
        }, [Vue.h(McUIVue.McSlider, {
            modelValue: control.value, min: 0, max: 1, step: 0.001,
            label: caption.label, showValue: false,
            disabled: !control.active, tabindex: -1,
            'aria-label': control.label, 'aria-valuetext': caption.value
        }), caption.value !== '' ? Vue.h('output', {
            class: 'mc-slider__value', 'aria-hidden': 'true'
        }, caption.value) : null]);
    }
    return Vue.h(McUIVue.McButton, {
        key: control.key,
        variant: control.selected || control.primary ? 'primary' : 'normal',
        size: 'middle', tabindex: -1, disabled: !control.active,
        class: ['xaeroearth-proxy', 'is-' + control.role,
            settings && control.role === 'button' ? 'is-setting' : '',
            control.selected ? 'is-selected' : ''],
        'data-native-key': control.key, style: xaeroMapStyleText(style)
    }, { default: function () { return xaeroMapSkinLabel(control); } });
}

function xaeroMapBounds(controls, padding) {
    var left = controls[0].x, top = controls[0].y, right = left, bottom = top;
    controls.forEach(function (control) {
        left = Math.min(left, control.x); top = Math.min(top, control.y);
        right = Math.max(right, control.x + control.width);
        bottom = Math.max(bottom, control.y + control.height);
    });
    return { position: 'absolute', left: (left - padding) + 'px', top: (top - padding) + 'px',
        width: (right - left + padding * 2) + 'px', height: (bottom - top + padding * 2) + 'px' };
}

function xaeroMapMenu(rows) {
    rows.sort(function (a, b) { return a.y - b.y; });
    var rowHeight = rows[0].height;
    var border = rowHeight / 11;
    var style = xaeroMapBounds(rows, border);
    style.zIndex = rows[0].expanded ? '10' : '1';
    style['--xaero-row-height'] = rowHeight + 'px';
    style['--xaero-menu-font'] = (rowHeight * 8 / 11) + 'px';
    style['--xaero-pixel'] = border + 'px';
    var selected = '';
    rows.forEach(function (row) { if (row.selected) selected = row.key; });
    return Vue.h(McUIVue.McList, {
        key: 'menu-' + rows[0].menuId, class: 'xaeroearth-menu', style: xaeroMapStyleText(style),
        mode: 'single', showRadio: false, modelValue: selected
    }, { default: function () {
        var currentRows = (xaeroMapSkinState.value.controls || []).filter(function (row) {
            return row.role === 'menu' && row.visible && row.menuId === rows[0].menuId;
        });
        currentRows.sort(function (a, b) { return a.y - b.y; });
        return currentRows.map(function (row) {
            return Vue.h(McUIVue.McListItem, {
                key: row.key, value: row.key, label: '', disabled: !row.active,
                interactive: row.active
            }, { left: function () {
                return Vue.h('span', { class: 'xaeroearth-menu-label', 'data-native-key': row.key }, xaeroMapSkinLabel(row));
            } });
        });
    } });
}

function xaeroMapSkinComponent() {
    return {
        render: function () {
            var state = xaeroMapSkinState.value;
            var controls = state.controls || [];
            var docks = {}, menus = {}, painted = [];
            controls.forEach(function (control) {
                if (control.role === 'menu') {
                    if (control.visible) (menus[control.menuId] || (menus[control.menuId] = [])).push(control);
                    return;
                }
                if (control.visible && control.dock) (docks[control.dock] || (docks[control.dock] = [])).push(control);
                painted.push(xaeroMapControl(control, state.settings));
            });
            var panels = Object.keys(docks).map(function (key) {
                var group = docks[key];
                var style = xaeroMapBounds(group, group[0].height * 0.15);
                return Vue.h(McUIVue.McPanel, { key: 'dock-' + key,
                    class: ['xaeroearth-dock', key === 'views' ? 'is-view-dock' : ''], style: xaeroMapStyleText(style) });
            });
            Object.keys(menus).forEach(function (key) { painted.push(xaeroMapMenu(menus[key])); });
            return Vue.h(McUIVue.McApp, {
                class: ['xaeroearth-app', state.settings ? 'is-settings' : ''],
                style: { position: 'absolute', left: '0', top: '0', width: '100%', height: '100%', background: 'transparent' }
            }, { default: function () {
                return [Vue.h(McUIVue.McPanel, { key: 'settings-surface', class: 'xaeroearth-settings-surface',
                    style: { display: 'none' } }), Vue.h('div', {
                    class: 'xaeroearth-controls', style: { display: state.visible !== false ? '' : 'none' }
                }, panels.concat(painted))];
            } });
        }
    };
}

function xaeroMapSkinUpdate(state) {
    xaeroMapSkinState.value = state;
}

function xaeroMapSkinInit() {
    if (xaeroMapSkinApp) xaeroMapSkinApp.unmount();
    xaeroMapSkinApp = Vue.createApp(xaeroMapSkinComponent());
    xaeroMapSkinApp.use(McUIVue.createMcUI({ sounds: { enabled: false } }));
    xaeroMapSkinApp.mount('#xaeroearth-map-skin');
}

function xaeroMapSkinUnload() {
    if (!xaeroMapSkinApp) return;
    xaeroMapSkinApp.unmount();
    xaeroMapSkinApp = null;
}

document.addEventListener('DOMContentLoaded', xaeroMapSkinInit);
document.body.addEventListener('unload', xaeroMapSkinUnload);
