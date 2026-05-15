on run argv
  set cmd to item 1 of argv
  set sourceTty to item 2 of argv
  set hasTty to (sourceTty is not "")
  set foundWindow to missing value
  tell application "iTerm"
    activate
    if (count of windows) is 0 then
      create window with default profile
    end if
    if hasTty then
      repeat with w in windows
        repeat with t in tabs of w
          repeat with s in sessions of t
            try
              if tty of s is sourceTty then
                set foundWindow to w
                exit repeat
              end if
            end try
          end repeat
          if foundWindow is not missing value then exit repeat
        end repeat
        if foundWindow is not missing value then exit repeat
      end repeat
    end if
    if foundWindow is missing value then
      set foundWindow to current window
    end if
    tell foundWindow
      create tab with default profile
      tell current session to write text cmd
    end tell
  end tell
end run
