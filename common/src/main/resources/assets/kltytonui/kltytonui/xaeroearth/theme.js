var xaeroMapSkinState = Vue.shallowRef({ controls: [], visible: false });
var xaeroMapOverlayState = Vue.shallowRef({});
var xaeroMapSkinApp = null;
var xaeroMapMountedControls = Object.create(null);
var xaeroMapPendingState = null;
var xaeroMapMountFrame = -1;
var xaeroMapPresented = false;

function xaeroMapSkinText(text) {
    return text.indexOf('\u00a7') < 0 ? text : Vue.h(McUIVue.McFormattedText, { text: text });
}

function xaeroMapSkinLabel(control) {
    if (control.role === 'icon') return Vue.h('span', { class: 'xaeroearth-native-anchor', 'aria-hidden': 'true' });
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

var xaeroMapAppStyle = { position: 'absolute', left: '0', top: '0', width: '100%', height: '100%', background: 'transparent' };
var xaeroMapProxyBaseStyle = 'position:absolute;left:-9999px;top:-9999px;visibility:hidden;';
var xaeroMapDockKeys = ['settings', 'tools-left', 'tools-right', 'views'];

function xaeroMapInnerVarsStyleText(control) {
    var unit = control.height / 20;
    var text = 'width:100%;height:100%;'
        + '--xaero-height:' + control.height + 'px;'
        + '--xaero-font-size:' + (9 * unit) + 'px;'
        + '--xaero-label-size:' + (8 * unit) + 'px;'
        + '--xaero-label-height:' + (8 * unit) + 'px;'
        + '--xaero-inset:' + (4 * unit) + 'px;'
        + '--xaero-pixel:' + unit + 'px;'
        + '--xaero-depth:' + (2 * unit) + 'px;';
    if (control.role === 'slider') {
        var scale = unit / 2;
        text += 'position:relative;box-sizing:border-box;padding:' + unit + 'px ' + (4 * unit) + 'px;'
            + '--xaero-slider-scale:' + scale + ';--xaero-slider-width:' + ((control.width - 8 * unit) / scale) + 'px;';
    }
    return text;
}

function xaeroMapControlInner(control, settings) {
    var varsStyle = xaeroMapInnerVarsStyleText(control);
    if (control.role === 'slider') {
        var caption = xaeroMapSliderCaption(control);
        return Vue.h('div', { style: varsStyle }, [Vue.h(McUIVue.McSlider, {
            modelValue: control.value, min: 0, max: 1, step: 0.001,
            label: caption.label, showValue: false,
            disabled: !control.active, tabindex: -1,
            'aria-label': control.label, 'aria-valuetext': caption.value
        }), caption.value !== '' ? Vue.h('output', {
            class: 'mc-slider__value', 'aria-hidden': 'true'
        }, caption.value) : null]);
    }
    return Vue.h('div', { style: varsStyle }, [Vue.h(McUIVue.McButton, {
        variant: control.selected || control.primary ? 'primary' : 'normal',
        size: 'middle', tabindex: -1,
        style: 'width:' + control.width + 'px;min-width:' + control.width + 'px;max-width:' + control.width
            + 'px;height:' + control.height + 'px;min-height:' + control.height
            + 'px;max-height:' + control.height + 'px;font-size:' + (control.height * 9 / 20) + 'px;'
    }, { default: function () { return xaeroMapSkinLabel(control); } })]);
}

function xaeroMapControl(control, settings, cache) {
    var runsKey = control.runs ? JSON.stringify(control.runs) : '';
    // Keep CSS custom properties and McButton/McSlider inside the memoized inner VNode,
    // and leave outer position/visibility to Java's updateFeedback() so Vue never patches
    // inline styles or re-cascades stylesheets when buttons move or toggle visibility.
    var inner = Vue.withMemo([control.role, control.label, runsKey, control.value, control.active,
        control.primary, control.selected, control.width, control.height, settings],
        function () { return xaeroMapControlInner(control, settings); }, cache, 0);
    if (control.role === 'slider') {
        return Vue.h('div', {
            key: control.key, class: 'xaeroearth-proxy is-slider',
            'data-native-key': control.key, style: xaeroMapProxyBaseStyle
        }, [inner]);
    }
    return Vue.h('div', {
        key: control.key,
        class: ['xaeroearth-proxy', 'is-' + control.role,
            settings && control.role === 'button' ? 'is-setting' : '',
            control.selected ? 'is-selected' : ''],
        'data-native-key': control.key, style: xaeroMapProxyBaseStyle
    }, [inner]);
}

function xaeroMapBounds(controls, padding) {
    var left = controls[0].x, top = controls[0].y, right = left, bottom = top;
    controls.forEach(function (control) {
        left = Math.min(left, control.x); top = Math.min(top, control.y);
        right = Math.max(right, control.x + control.width);
        bottom = Math.max(bottom, control.y + control.height);
    });
    return { left: (left - padding) + 'px', top: (top - padding) + 'px',
        width: (right - left + padding * 2) + 'px', height: (bottom - top + padding * 2) + 'px' };
}

function xaeroMapDockStyleText(bounds) {
    return 'position:absolute;left:' + bounds.left + ';top:' + bounds.top
        + ';width:' + bounds.width + ';height:' + bounds.height + ';';
}

function xaeroMapMenu(rows) {
    rows.sort(function (a, b) { return a.y - b.y; });
    var rowHeight = rows[0].height;
    var border = rowHeight / 11;
    var style = xaeroMapBounds(rows, border);
    var styleText = xaeroMapDockStyleText(style)
        + 'z-index:' + (rows[0].expanded ? '10' : '1') + ';'
        + '--xaero-row-height:' + rowHeight + 'px;'
        + '--xaero-menu-font:' + (rowHeight * 8 / 11) + 'px;'
        + '--xaero-pixel:' + border + 'px;';
    var selected = '';
    rows.forEach(function (row) { if (row.selected) selected = row.key; });
    return Vue.h(McUIVue.McList, {
        key: 'menu-' + rows[0].menuId, class: 'xaeroearth-menu', style: styleText,
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
                return Vue.h('span', {
                    class: 'xaeroearth-menu-label', 'data-native-key': row.key,
                    style: 'font-size:' + (row.height * 8 / 11) + 'px;line-height:' + row.height + 'px;'
                }, xaeroMapSkinLabel(row));
            } });
        });
    } });
}

function xaeroMapRuler(ruler, width, height) {
    if (!ruler) return [];
    var result = [];
    var place = function (x, y) { return 'left:' + x + 'px;top:' + y + 'px;'; };
    var dot = function (x, y, key) {
        return Vue.h('div', { key: key, class: 'xaeroearth-ruler-point', style: place(x - 3, y - 3) });
    };
    var x = ruler.x, y = ruler.y;
    if (typeof ruler.endX === 'number') {
        var dx = ruler.endX - x, dy = ruler.endY - y;
        result.push(Vue.h('div', {
            key: 'ruler-line', class: 'xaeroearth-ruler-line',
            style: place(x, y - 1) + 'width:' + Math.sqrt(dx * dx + dy * dy) + 'px;'
                + 'transform:rotate(' + (Math.atan2(dy, dx) * 180 / Math.PI) + 'deg);'
        }));
        result.push(dot(ruler.endX, ruler.endY, 'ruler-end'));
        x += dx / 2;
        y += dy / 2;
    }
    result.push(dot(ruler.x, ruler.y, 'ruler-start'));
    if (x > -20 && x < width + 20 && y > -20 && y < height + 20) {
        result.push(Vue.h('span', {
            key: 'ruler-label', class: 'xaeroearth-ruler-label',
            style: place(Math.max(4, Math.min(width - 4, x)), Math.max(4, Math.min(height - 24, y + 7)))
        }, ruler.label));
    }
    return result;
}

function xaeroMapSkinComponent() {
    var cachedButtons = Object.create(null);
    var cachedDocks = Object.create(null);
    return {
        render: function () {
            var state = xaeroMapSkinState.value;
            var controls = state.controls || [];
            var menus = {}, painted = [];
            var currentButtons = Object.create(null);
            var currentDocks = Object.create(null);
            controls.forEach(function (control) {
                if (control.role === 'menu') {
                    if (!state.mounting && control.visible) (menus[control.menuId] || (menus[control.menuId] = [])).push(control);
                    return;
                }
                // Keep elements mounted by an earlier frame out of the next Vue patch.
                var cache = cachedButtons[control.key] || [];
                currentButtons[control.key] = cache;
                painted.push(xaeroMapControl(control, state.settings, cache));
            });
            cachedButtons = currentButtons;
            var panels = (!state.settings && !state.mounting) ? xaeroMapDockKeys.map(function (key) {
                var dockCache = cachedDocks[key] || [];
                currentDocks[key] = dockCache;
                return Vue.withMemo([key], function () {
                    return Vue.h(McUIVue.McPanel, {
                        key: 'dock-' + key, 'data-dock-key': key,
                        class: ['xaeroearth-dock', key === 'views' ? 'is-view-dock' : ''],
                        style: xaeroMapProxyBaseStyle
                    });
                }, dockCache, 0);
            }) : [];
            cachedDocks = currentDocks;
            Object.keys(menus).forEach(function (key) { painted.push(xaeroMapMenu(menus[key])); });
            return Vue.h(McUIVue.McApp, {
                class: ['xaeroearth-app', state.settings ? 'is-settings' : ''],
                style: xaeroMapAppStyle
            }, { default: function () {
                var visible = state.visible !== false && !state.mounting;
                return [Vue.h(McUIVue.McPanel, { key: 'settings-surface', class: 'xaeroearth-settings-surface',
                    style: { display: 'none' } }), Vue.h(xaeroMapOverlayLayer, { visible: visible }), Vue.h('div', {
                    key: 'map-controls', class: 'xaeroearth-controls', style: { display: visible ? '' : 'none' }
                }, panels.concat(painted))];
            } });
        }
    };
}

// Pan and ruler updates only invalidate this layer. The button and dock nodes keep their positions.
var xaeroMapOverlayLayer = {
    props: ['visible'],
    render: function () {
        var state = xaeroMapOverlayState.value;
        var items = [];
        if (this.visible) {
            items = xaeroMapRuler(state.ruler, state.width, state.height);
            (state.marks || []).forEach(function (mark) {
                items.push(Vue.h('span', {
                    key: mark.id, class: 'xaeroearth-ruler-label',
                    style: 'left:' + mark.x + 'px;top:' + mark.y + 'px;'
                }, mark.text));
            });
            if (state.remainingDistance) items.push(Vue.h('span', {
                key: 'remaining-distance', class: 'xaeroearth-navigation-distance'
            }, state.remainingDistance));
        }
        return Vue.h('div', { class: 'xaeroearth-controls' }, items);
    }
};

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
    xaeroMapCancelMount();
    xaeroMapMountedControls = Object.create(null);
}

function xaeroMapCancelMount() {
    if (xaeroMapMountFrame !== -1) window.cancelAnimationFrame(xaeroMapMountFrame);
    xaeroMapMountFrame = -1;
    xaeroMapPendingState = null;
}

function xaeroMapMountControls() {
    xaeroMapMountFrame = -1;
    var state = xaeroMapPendingState;
    if (!state) return;
    var mounting = state.visible && !state.settings && !xaeroMapPresented;
    var remaining = mounting ? 3 : state.controls.length;
    var hiddenBudget = !mounting && state.visible && !state.settings ? 2 : 0;
    var pending = false;
    var moreHidden = false;
    var currentControls = Object.create(null);
    var controls = state.controls.filter(function (control) {
        if (control.role === 'menu') return control.visible;
        if (!xaeroMapMountedControls[control.key]) {
            if (!control.initiallyVisible) {
                // Lazily warm hidden map controls 2 per idle frame once visible controls are ready
                // so revealing a hidden button (e.g. follow/center on drag start) only flips visibility in Java.
                if (hiddenBudget === 0) { moreHidden = true; return false; }
                hiddenBudget--;
            } else {
                if (remaining === 0) { pending = true; return false; }
                remaining--;
            }
        }
        currentControls[control.key] = true;
        return true;
    });
    xaeroMapMountedControls = currentControls;
    document.getElementById('xaeroearth-map-skin').setAttribute('data-earth-mounting', pending ? 'true' : 'false');
    xaeroMapSkinState.value = Object.assign({}, state, { controls: controls, mounting: pending, visible: state.visible && !pending });
    if (pending) {
        xaeroMapMountFrame = window.requestAnimationFrame(xaeroMapMountControls);
    } else {
        if (mounting) xaeroMapPresented = true;
        if (moreHidden && state.visible && !state.settings) {
            xaeroMapMountFrame = window.requestAnimationFrame(xaeroMapMountControls);
        } else {
            xaeroMapPendingState = null;
        }
    }
}

document.addEventListener('DOMContentLoaded', xaeroMapSkinInit);
document.body.addEventListener('xaeroearth-skin-state', function (event) {
    if (event.detail === null) {
        xaeroMapCancelMount();
        // Keep the keyed controls mounted so reopening does not recreate the whole skin.
        if (xaeroMapSkinState.value.visible !== false) {
            xaeroMapSkinState.value = Object.assign({}, xaeroMapSkinState.value, { visible: false });
        }
        return;
    }
    xaeroMapPendingState = JSON.parse(event.detail);
    if (xaeroMapMountFrame === -1) xaeroMapMountControls();
});
document.body.addEventListener('xaeroearth-skin-overlay', function (event) {
    xaeroMapOverlayState.value = event.detail === null ? {} : JSON.parse(event.detail);
});
document.body.addEventListener('unload', xaeroMapSkinUnload);
