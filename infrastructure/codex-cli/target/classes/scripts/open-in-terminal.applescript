on run argv
  set cmd to item 1 of argv
  set sourceTty to item 2 of argv
  set hasTty to (sourceTty is not "")
  set targetWindowId to missing value
  tell application "Terminal"
    if hasTty then
      repeat with w in windows
        repeat with t in tabs of w
          try
            if tty of t is sourceTty then
              set targetWindowId to id of w
              exit repeat
            end if
          end try
        end repeat
        if targetWindowId is not missing value then exit repeat
      end repeat
    end if
    activate
    if (count of windows) is 0 then
      do script cmd
      return
    end if
    if targetWindowId is missing value then
      set targetWindow to front window
    else
      set targetWindow to first window whose id is targetWindowId
      set index of targetWindow to 1
    end if
    tell application "System Events" to keystroke "t" using command down
    delay 0.2
    do script cmd in selected tab of targetWindow
  end tell
end run
