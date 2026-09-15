(function(){
 const id=window.OWNERLINK_DEVICE_ID, status=document.getElementById("location-status"), result=document.getElementById("location-result"), button=document.getElementById("location-refresh");
 async function load(){button.disabled=true;status.textContent="Requesting location from phone…";result.textContent="";try{const d=await OwnerLink.api(`/api/devices/${id}/location`);const when=new Date(d.timestamp).toLocaleString();result.innerHTML=`<p><strong>${d.latitude.toFixed(6)}, ${d.longitude.toFixed(6)}</strong></p><p>Accuracy: about ${Math.round(d.accuracy_m)} m · Updated ${when}</p><p><a class="primary" target="_blank" rel="noopener" href="https://www.google.com/maps?q=${encodeURIComponent(d.latitude+','+d.longitude)}">Open in map</a></p>`;status.textContent="";}catch(e){status.textContent=e.message;}finally{button.disabled=false;}}
 button.addEventListener("click",load);
})();
