param([string]$Path)
[xml]$xml = Get-Content -Path $Path -Raw
$nodes = $xml.SelectNodes('//node')
foreach ($n in $nodes) {
    $text = $n.text
    $desc = $n.'content-desc'
    if ($text -or $desc) {
        Write-Output ("[{0}] text='{1}' desc='{2}' bounds={3}" -f $n.class, $text, $desc, $n.bounds)
    }
}
