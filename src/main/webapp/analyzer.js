function onMetricsCheckboxClick() {
  var el = window.event.currentTarget;
  if(el.checked === false) {
    return;
  }
  var table = el;
  while(table !== null && table.tagName !== 'TABLE') {
    table = table.parentElement;
  }
  if(table === null) return;

  var checkboxes = table.getElementsByTagName('INPUT');
  if(el.name === '_.ALL') {
    for(var i=0; i<checkboxes.length; i++) {
      if(checkboxes[i].name !== '_.ALL') {
        checkboxes[i].checked = false;
      }
    }
  } else {
    for(var i=0; i<checkboxes.length; i++) {
      if(checkboxes[i].name === '_.ALL') {
        checkboxes[i].checked = false;
      }
    }
  }
}
